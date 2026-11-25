package dev.abustudio.core;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Chooses provider+model from the test results, and fails over automatically.
 * "Best" here only means: best according to the tests that were actually run on this machine (tool-call test +
 * latency), your preference and priority. It is not a claim that a model is the strongest in general.
 */
public final class Router
{
    public record Candidate(ProviderConfig p, String model, int score, boolean verified) {}

    public static class AllFailedException extends Exception
    {
        public final boolean networkDown;
        public final List<String> reasons;
        public final long retryInMs;

        public AllFailedException(boolean networkDown, List<String> reasons, long retryInMs)
        {
            super(networkDown ? "الإنترنت مقطوع ولا توجد واجهة محلية متاحة" : "تعطلت جميع الواجهات");
            this.networkDown = networkDown;
            this.reasons = reasons;
            this.retryInMs = retryInMs;
        }
    }

    private final Supplier<StudioConfig> cfg;
    private final SecretStore secrets;
    private final Notifier notifier;
    private final Map<String, Long> providerCooldown = new ConcurrentHashMap<>();
    private final Map<String, Long> modelCooldown = new ConcurrentHashMap<>();
    private volatile String lastUsed = "";
    private volatile String currentAttempt = "";
    public volatile long backoffBaseMs = 2000;

    public Router(Supplier<StudioConfig> cfg, SecretStore secrets, Notifier notifier)
    {
        this.cfg = cfg;
        this.secrets = secrets;
        this.notifier = notifier;
    }

    public String lastUsed() { return lastUsed; }

    /** Provider + model being tried right now ("" when nothing is in flight). */
    public String currentAttempt() { return currentAttempt; }

    /** Configuration readiness only; does not claim credentials work. */
    public String setupProblem() {
        boolean enabled = false, hasKey = false, hasModel = false;
        for (ProviderConfig p : cfg.get().providers) {
            if (!p.enabled) continue;
            enabled = true;
            boolean key = "none".equals(p.authMode) || secrets.has("provider:" + p.id);
            if (key) hasKey = true;
            boolean model = !p.models.isEmpty() || !p.preferredModel.isBlank();
            if (model) hasModel = true;
            if (key && model && !p.baseUrl.isBlank()) return "";
        }
        if (!enabled) return "لا يوجد مزود مفعّل. افتح الإعدادات ثم المزودون وأضف مزودًا.";
        if (!hasKey) return "لا يوجد مفتاح API محفوظ. افتح الإعدادات ثم المزودون، واختر Groq وأدخل مفتاحك واحفظه.";
        if (!hasModel) return "لا يوجد موديل مضبوط. افتح المزود واختر موديلًا أو اجلب الموديلات.";
        return "إعداد المزود غير مكتمل. تحقق من العنوان والمفتاح والموديل من الإعدادات.";
    }

    public void clearCooldowns() { providerCooldown.clear(); modelCooldown.clear(); }

    public long soonestRecoveryMs()
    {
        long now = System.currentTimeMillis();
        long best = Long.MAX_VALUE;
        for (long t : providerCooldown.values()) if (t > now) best = Math.min(best, t - now);
        return best == Long.MAX_VALUE ? 30000 : Math.max(5000, Math.min(best, 120000));
    }

    public List<Candidate> candidates(boolean online)
    {
        long now = System.currentTimeMillis();
        List<Candidate> out = new ArrayList<>();

        for (ProviderConfig p : cfg.get().providers)
        {
            if (!p.enabled) continue;
            if (!online && !p.isLocal()) continue;
            if (providerCooldown.getOrDefault(p.id, 0L) > now) continue;

            List<String> models = new ArrayList<>(p.models);
            if (!p.preferredModel.isBlank() && !models.contains(p.preferredModel)) models.add(p.preferredModel);

            for (String m : models)
            {
                if (modelCooldown.getOrDefault(p.id + "|" + m, 0L) > now) continue;
                ProviderConfig.ModelProbe pr = p.probes.get(m);
                int score;
                boolean verified = false;

                if (pr == null) score = 300;                                   /* never tested: allowed, ranked below tested ones */
                else if (pr.toolsOk) { score = 1000 - (int) Math.min(300, pr.latencyMs / 20); verified = true; }
                else continue;                                                  /* tested and failed the tool test: the agent needs tools */

                if (m.equals(p.preferredModel)) score += 150;
                if (!online) score += 100;
                score -= p.priority / 10;
                out.add(new Candidate(p, m, score, verified));
            }
        }

        out.sort((a, b) -> Integer.compare(b.score, a.score));
        return out;
    }

    public Llm.Result chat(List<Llm.Msg> msgs, List<Llm.ToolSpec> tools, Control control) throws AllFailedException, Control.StopException
    {
        List<String> reasons = new ArrayList<>();
        String problem = setupProblem();
        if (!problem.isEmpty()) throw new AllFailedException(false, List.of(problem), 30000);
        boolean online = NetMonitor.online();
        Set<String> failedNow = new HashSet<>();
        boolean sawNetworkFailure = false;

        while (true)
        {
            if (control != null && control.isStopped()) throw new Control.StopException();
            List<Candidate> list = candidates(online);
            list.removeIf(c -> failedNow.contains(c.p.id + "|" + c.model));

            if (list.isEmpty())
            {
                boolean down = !NetMonitor.online(true);
                if (down && !sawNetworkFailure) sawNetworkFailure = true;
                throw new AllFailedException(down || (sawNetworkFailure && !NetMonitor.online(true)), reasons, soonestRecoveryMs());
            }

            Candidate c = list.get(0);
            Candidate next = list.size() > 1 ? list.get(1) : null;
            String key = secrets.get("provider:" + c.p.id);
            int tries = 0;

            while (true)
            {
                tries++;

                try
                {
                    currentAttempt = c.p.name + " / " + c.model;
                    Llm.Result r = LlmClient.chat(c.p, key, c.model, msgs, tools);
                    String used = c.p.name + " / " + c.model;
                    lastUsed = used;
                    return r;
                }
                catch (Llm.LlmException e)
                {
                    boolean retry = e.kind.transientError && tries < 2 && !(e.kind == Llm.Failure.NETWORK && !c.p.isLocal() && !NetMonitor.online(true));

                    if (retry)
                    {
                        control.sleep(backoffBaseMs * tries + (long) (Math.random() * 500));
                        continue;
                    }

                    failedNow.add(c.p.id + "|" + c.model);
                    long now = System.currentTimeMillis();

                    switch (e.kind)
                    {
                        case QUOTA -> providerCooldown.put(c.p.id, now + 30 * 60_000L);
                        case RATE_LIMIT -> providerCooldown.put(c.p.id, now + Math.max(e.retryAfterMs, 60_000L));
                        case AUTH -> providerCooldown.put(c.p.id, now + 10 * 60_000L);
                        case MODEL_MISSING -> modelCooldown.put(c.p.id + "|" + c.model, now + 60 * 60_000L);
                        case NO_TOOLS ->
                        {
                            modelCooldown.put(c.p.id + "|" + c.model, now + 60 * 60_000L);
                            ProviderConfig.ModelProbe pr = c.p.probes.computeIfAbsent(c.model, k -> new ProviderConfig.ModelProbe());
                            pr.toolsOk = false; pr.note = "فشل استدعاء الأدوات أثناء العمل"; pr.testedAt = now;
                        }
                        case NETWORK ->
                        {
                            if (c.p.isLocal()) providerCooldown.put(c.p.id, now + 15_000L);
                            else { sawNetworkFailure = true; online = NetMonitor.online(true); if (online) providerCooldown.put(c.p.id, now + 20_000L); }
                        }
                        default -> providerCooldown.put(c.p.id, now + 60_000L);
                    }

                    String why = e.kind.arabic + (e.status > 0 ? " (HTTP " + e.status + ")" : "");
                    reasons.add(c.p.name + "/" + c.model + ": " + why);
                    String msg = "⚠️ تعطل المزود «" + c.p.name + "» والموديل «" + c.model + "»\nالسبب: " + why;
                    msg += next != null ? "\n↪️ تم التبديل إلى «" + next.p.name + "» والموديل «" + next.model + "» والمتابعة من نقطة التوقف."
                        : "\n⏸ لا يوجد بديل متاح حاليًا.";
                    notifier.notify(msg);
                    Log.warn("تبديل المزود: " + c.p.name + "/" + c.model + " -> " + why);
                    break;
                }
            }
        }
    }
}
