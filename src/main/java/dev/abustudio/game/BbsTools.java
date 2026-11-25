package dev.abustudio.game;

import com.google.gson.*;
import dev.abustudio.core.*;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.data.DataToString;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.FilmManager;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import net.minecraft.server.network.ServerPlayerEntity;

import java.nio.file.*;
import java.util.*;

/**
 * Real BBS work: films are created/edited through BBS's own classes and saved with BBS's own FilmManager (which keeps
 * its backups), so BBS opens them normally. Formats are discovered at runtime with the *_template tools.
 */
public final class BbsTools
{
    private BbsTools() {}

    static FilmManager films() throws Exception
    {
        FilmManager fm = BBSMod.getFilms();
        if (fm == null) throw new IllegalStateException("BBS غير جاهز: افتح عالمًا أولًا (الأفلام تُحفظ داخل العالم).");
        return fm;
    }

    static Film load(String name) throws Exception
    {
        Film f = films().load(name);
        if (f == null) throw new IllegalArgumentException("الفيلم «" + name + "» غير موجود. الموجود: " + films().getKeys());
        return f;
    }

    static void save(String name, Film f) throws Exception
    {
        BaseType d = f.toData();
        if (!films().save(name, d.asMap())) throw new IllegalStateException("فشل حفظ الفيلم «" + name + "»");
    }

    static String json(BaseType t, boolean pretty) { return DataToString.toString(t, pretty); }

    /** Recursively merges patch into base (maps merge, everything else replaces). */
    static void merge(MapType base, MapType patch)
    {
        for (String k : patch.keys())
        {
            BaseType pv = patch.get(k);
            BaseType bv = base.get(k);
            if (pv.isMap() && bv != null && bv.isMap()) merge(bv.asMap(), pv.asMap());
            else base.put(k, pv);
        }
    }

    /** Builds a form from BBS's own default template of that type, then overlays the given properties. */
    public static Form makeForm(String json) throws Exception
    {
        MapType given = DataToString.mapFromString(json);
        String type = "bbs:model";
        for (String k : new String[]{"type", "_type", "form_type"})
            if (given.has(k) && given.get(k).isString()) { type = given.get(k).asString(); break; }
        if (!type.contains(":")) type = "bbs:" + type;
        Form base = BBSMod.getForms().create(Link.create(type));
        if (base == null) throw new IllegalArgumentException("نوع النموذج غير معروف: " + type + " (انظر bbs_form_template بدون type)");
        MapType d = FormUtils.toData(base);
        MapType over = new MapType();
        for (String k : given.keys()) if (!k.equals("type") && !k.equals("_type") && !k.equals("form_type")) over.put(k, given.get(k));
        merge(d, over);
        Form f = FormUtils.fromData(d);
        if (f == null) throw new IllegalArgumentException("تعذر بناء النموذج من البيانات");
        return f;
    }

    static Replay replay(Film f, String ref)
    {
        for (Replay r : f.replays.getList()) if (r.getId().equals(ref) || r.label.get().equals(ref)) return r;
        try { int i = Integer.parseInt(ref); if (i >= 0 && i < f.replays.getList().size()) return f.replays.getList().get(i); } catch (NumberFormatException ignored) {}
        throw new IllegalArgumentException("الشخصية (replay) «" + ref + "» غير موجودة في الفيلم. الموجود: " + f.replays.getList().stream().map(r -> r.getId() + "=" + r.label.get()).toList());
    }

    static KeyframeChannel<Double> channel(Replay r, String ch)
    {
        return switch (ch)
        {
            case "x" -> r.keyframes.x; case "y" -> r.keyframes.y; case "z" -> r.keyframes.z;
            case "yaw" -> r.keyframes.yaw; case "pitch" -> r.keyframes.pitch;
            case "headYaw", "head_yaw" -> r.keyframes.headYaw; case "bodyYaw", "body_yaw" -> r.keyframes.bodyYaw;
            case "vX" -> r.keyframes.vX; case "vY" -> r.keyframes.vY; case "vZ" -> r.keyframes.vZ;
            case "sneaking" -> r.keyframes.sneaking; case "sprinting" -> r.keyframes.sprinting; case "grounded" -> r.keyframes.grounded;
            case "roll" -> r.keyframes.roll; case "leaning" -> r.keyframes.leaning;
            default -> throw new IllegalArgumentException("قناة غير معروفة: " + ch + " (المتاح: x,y,z,yaw,pitch,headYaw,bodyYaw,sneaking,sprinting,grounded,roll,leaning)");
        };
    }

    static String truncate(String s, int max) { return s.length() <= max ? s : s.substring(0, max) + "\n…[اقتُطع؛ اطلب section أصغر]"; }

    public static void register(Studio s)
    {
        Tools t = s.tools;
        final String F = "\"film\":{\"type\":\"string\",\"description\":\"اسم الفيلم\"}";

        t.add("bbs_list_films", "اذكر أفلام BBS المحفوظة في هذا العالم.", "{\"properties\":{}}", false, (a, c) -> GameTools.onServer(s, () ->
        {
            try { JsonObject o = Tools.ok(); JsonArray arr = new JsonArray(); for (String k : films().getKeys()) arr.add(k); o.add("films", arr); return o; }
            catch (Exception e) { return Tools.err(e.getMessage()); }
        }));

        t.add("bbs_form_template", "اعرض بيانات نموذج BBS الافتراضي لنوع معين (model, mob, billboard, block, item, label, particle…) أو اذكر الأنواع إن لم تمرر type.",
            "{\"properties\":{\"type\":{\"type\":\"string\"}}}", false, (a, c) -> GameTools.onServer(s, () ->
            {
                try
                {
                    String ty = Json.str(a, "type", "");
                    JsonObject o = Tools.ok();
                    if (ty.isBlank()) { JsonArray arr = new JsonArray(); for (Link l : BBSMod.getForms().getKeys()) arr.add(l.toString()); o.add("types", arr); return o; }
                    Form f = BBSMod.getForms().create(Link.create(ty.contains(":") ? ty : "bbs:" + ty));
                    o.addProperty("template", truncate(json(FormUtils.toData(f), false), 6000));
                    return o;
                }
                catch (Exception e) { return Tools.err(e.getMessage()); }
            }));

        t.add("bbs_clip_template", "اعرض بيانات كليب افتراضي. target=camera (idle, dolly, path, keyframe, look, orbit, shake, subtitle, audio, image…) أو action (حركات/أحداث الشخصية). بدون type يعرض الأنواع.",
            "{\"properties\":{\"target\":{\"type\":\"string\"},\"type\":{\"type\":\"string\"}}}", false, (a, c) -> GameTools.onServer(s, () ->
            {
                try
                {
                    var factory = Json.str(a, "target", "camera").equals("action") ? BBSMod.getFactoryActionClips() : BBSMod.getFactoryCameraClips();
                    String ty = Json.str(a, "type", "");
                    JsonObject o = Tools.ok();
                    if (ty.isBlank()) { JsonArray arr = new JsonArray(); for (Link l : factory.getKeys()) arr.add(l.toString()); o.add("types", arr); return o; }
                    Clip clip = factory.create(Link.create(ty.contains(":") ? ty : "bbs:" + ty));
                    o.addProperty("template", truncate(json(clip.toData(), false), 6000));
                    return o;
                }
                catch (Exception e) { return Tools.err(e.getMessage()); }
            }));

        t.add("bbs_film_create", "أنشئ فيلم BBS جديدًا (يفشل إن كان الاسم موجودًا إلا مع overwrite=true، وعندها تؤخذ نسخة احتياطية).",
            "{\"properties\":{" + F + ",\"description\":{\"type\":\"string\"},\"overwrite\":{\"type\":\"boolean\"}},\"required\":[\"film\"]}", true, (a, c) ->
            {
                GameTools.backup(s, c);
                return GameTools.onServer(s, () ->
                {
                    try
                    {
                        String n = Json.str(a, "film", "");
                        if (!n.matches("[A-Za-z0-9_-]{1,48}")) return Tools.err("اسم الفيلم يجب أن يكون لاتينيًا (A-Z a-z 0-9 _ -) لأن بعض الأنظمة لا تدعم الأسماء العربية لملفات الحفظ. ضع العنوان العربي في description.");
                        if (films().exists(n) && !Json.bool(a, "overwrite", false)) return Tools.err("الفيلم موجود بالفعل. استخدم bbs_film_get ثم عدّله، أو overwrite=true.");
                        Film f = new Film();
                        f.stampCreationTimeNow();
                        f.description.set(Json.str(a, "description", ""));
                        save(n, f);
                        JsonObject o = Tools.ok();
                        o.addProperty("film", n);
                        return o;
                    }
                    catch (Exception e) { return Tools.err(e.getMessage()); }
                });
            }).verify(null, (a, pre, c) -> GameTools.onServer(s, () -> { try { return films().exists(Json.str(a, "film", "")); } catch (Exception e) { return null; } }));

        t.add("bbs_film_get", "اقرأ فيلمًا كـ JSON. section: summary (افتراضي: ملخص الشخصيات والكليبات) | camera | replays | full.",
            "{\"properties\":{" + F + ",\"section\":{\"type\":\"string\"}},\"required\":[\"film\"]}", false, (a, c) -> GameTools.onServer(s, () ->
            {
                try
                {
                    Film f = load(Json.str(a, "film", ""));
                    String sec = Json.str(a, "section", "summary");
                    JsonObject o = Tools.ok();
                    MapType d = f.toData().asMap();

                    switch (sec)
                    {
                        case "full" -> o.addProperty("data", truncate(json(d, false), 14000));
                        case "camera" -> o.addProperty("data", truncate(json(d.get("camera"), false), 14000));
                        case "replays" -> o.addProperty("data", truncate(json(d.get("replays"), false), 14000));
                        default ->
                        {
                            o.addProperty("duration_ticks", f.camera.calculateDuration());
                            JsonArray reps = new JsonArray();
                            for (Replay r : f.replays.getList()) reps.add(r.getId() + " = " + r.label.get() + " (keyframes x:" + r.keyframes.x.getKeyframes().size() + ")");
                            o.add("replays", reps);
                            JsonArray clips = new JsonArray();
                            for (Clip cl : f.camera.get()) clips.add(cl.getClass().getSimpleName() + " tick=" + cl.tick.get() + " dur=" + cl.duration.get() + " layer=" + cl.layer.get() + " «" + cl.title.get() + "»");
                            o.add("camera_clips", clips);
                            o.addProperty("description", f.description.get());
                        }
                    }
                    return o;
                }
                catch (Exception e) { return Tools.err(e.getMessage()); }
            }));

        t.add("bbs_film_add_replay", "أضف شخصية (replay) للفيلم بنموذجها واسمها وموضعها الابتدائي. form_json بصيغة BBS (من bbs_form_template). actor=true يجعلها كيانًا حقيقيًا أثناء التشغيل.",
            "{\"properties\":{" + F + ",\"label\":{\"type\":\"string\"},\"form_json\":{\"type\":\"string\"},\"x\":{\"type\":\"number\"},\"y\":{\"type\":\"number\"},\"z\":{\"type\":\"number\"},\"yaw\":{\"type\":\"number\"},\"name_tag\":{\"type\":\"string\"},\"actor\":{\"type\":\"boolean\"}},\"required\":[\"film\",\"label\"]}", true, (a, c) -> GameTools.onServer(s, () ->
            {
                try
                {
                    String n = Json.str(a, "film", "");
                    Film f = load(n);
                    String label = Json.str(a, "label", "");
                    for (Replay r : f.replays.getList()) if (r.label.get().equals(label)) { JsonObject o = Tools.ok(); o.addProperty("replay", r.getId()); o.addProperty("note", "كانت موجودة بالفعل (لم تُكرَّر)"); return o; }
                    Replay r = f.replays.addReplay();
                    r.label.set(label);
                    r.nameTag.set(Json.str(a, "name_tag", ""));
                    r.actor.set(Json.bool(a, "actor", true));
                    String fj = Json.str(a, "form_json", "");
                    if (!fj.isBlank())
                    {
                        r.form.set(makeForm(fj));
                    }
                    r.keyframes.x.insert(0, Json.dbl(a, "x", 0));
                    r.keyframes.y.insert(0, Json.dbl(a, "y", 64));
                    r.keyframes.z.insert(0, Json.dbl(a, "z", 0));
                    r.keyframes.yaw.insert(0, Json.dbl(a, "yaw", 0));
                    save(n, f);
                    JsonObject o = Tools.ok();
                    o.addProperty("replay", r.getId());
                    return o;
                }
                catch (Exception e) { return Tools.err(e.getMessage()); }
            }));

        t.add("bbs_film_add_keyframes", "أضف مفاتيح حركة لقناة شخصية: channel من x,y,z,yaw,pitch,headYaw,bodyYaw,sneaking,sprinting,roll. keys=[{tick,value}] (20 tick = ثانية).",
            "{\"properties\":{" + F + ",\"replay\":{\"type\":\"string\",\"description\":\"id أو label أو رقم\"},\"channel\":{\"type\":\"string\"},\"keys\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{\"tick\":{\"type\":\"number\"},\"value\":{\"type\":\"number\"}}}}},\"required\":[\"film\",\"replay\",\"channel\",\"keys\"]}", true, (a, c) -> GameTools.onServer(s, () ->
            {
                try
                {
                    String n = Json.str(a, "film", "");
                    Film f = load(n);
                    Replay r = replay(f, Json.str(a, "replay", ""));
                    KeyframeChannel<Double> ch = channel(r, Json.str(a, "channel", ""));
                    int count = 0;
                    for (JsonElement e : a.getAsJsonArray("keys")) { JsonObject k = e.getAsJsonObject(); ch.insert((float) Json.dbl(k, "tick", 0), Json.dbl(k, "value", 0)); count++; }
                    save(n, f);
                    JsonObject o = Tools.ok();
                    o.addProperty("added", count);
                    o.addProperty("channel_keyframes_now", ch.getKeyframes().size());
                    return o;
                }
                catch (Exception e) { return Tools.err(e.getMessage()); }
            }));

        t.add("bbs_film_add_clip", "أضف كليبًا: target=camera (كاميرا/ترجمة/صوت…) أو action (حدث لشخصية؛ يلزم replay). patch_json يُدمج فوق القالب (انظر bbs_clip_template).",
            "{\"properties\":{" + F + ",\"target\":{\"type\":\"string\"},\"replay\":{\"type\":\"string\"},\"type\":{\"type\":\"string\"},\"tick\":{\"type\":\"integer\"},\"duration\":{\"type\":\"integer\"},\"title\":{\"type\":\"string\"},\"layer\":{\"type\":\"integer\"},\"patch_json\":{\"type\":\"string\"}},\"required\":[\"film\",\"type\",\"tick\",\"duration\"]}", true, (a, c) -> GameTools.onServer(s, () ->
            {
                try { return addClip(a); } catch (Exception e) { return Tools.err(e.getMessage()); }
            }));

        t.add("bbs_film_add_subtitle", "أضف ترجمة/حوارًا نصيًا (عربي) على الشاشة في وقت محدد.",
            "{\"properties\":{" + F + ",\"text\":{\"type\":\"string\"},\"tick\":{\"type\":\"integer\"},\"duration\":{\"type\":\"integer\"}},\"required\":[\"film\",\"text\",\"tick\",\"duration\"]}", true, (a, c) -> GameTools.onServer(s, () ->
            {
                try
                {
                    JsonObject b = new JsonObject();
                    b.addProperty("film", Json.str(a, "film", "")); b.addProperty("type", "subtitle");
                    b.addProperty("tick", Json.num(a, "tick", 0)); b.addProperty("duration", Json.num(a, "duration", 40));
                    b.addProperty("title", Json.str(a, "text", ""));
                    JsonObject patch = new JsonObject();
                    patch.addProperty("text", Json.str(a, "text", ""));
                    b.addProperty("patch_json", Json.COMPACT.toJson(patch));
                    return addClip(b);
                }
                catch (Exception e) { return Tools.err(e.getMessage()); }
            }));

        t.add("bbs_film_set_json", "استبدل جزءًا من الفيلم (camera أو replays أو أي مفتاح علوي) ببيانات JSON كاملة بصيغة BBS (خذها من bbs_film_get). تؤخذ نسخة احتياطية تلقائيًا من BBS عند الحفظ.",
            "{\"properties\":{" + F + ",\"key\":{\"type\":\"string\"},\"json\":{\"type\":\"string\"}},\"required\":[\"film\",\"key\",\"json\"]}", true, (a, c) -> GameTools.onServer(s, () ->
            {
                try
                {
                    String n = Json.str(a, "film", "");
                    Film f = load(n);
                    MapType d = f.toData().asMap();
                    d.put(Json.str(a, "key", ""), DataToString.fromString(Json.str(a, "json", "")));
                    Film nf = new Film();
                    nf.fromData(d);
                    save(n, nf);
                    return Tools.ok();
                }
                catch (Exception e) { return Tools.err(e.getMessage()); }
            }));

        t.add("bbs_film_play", "شغّل الفيلم للاعب داخل العالم (camera=true لتشغيل الكاميرا). يعيد ناتج أمر BBS الحقيقي.",
            "{\"properties\":{" + F + ",\"camera\":{\"type\":\"boolean\"}},\"required\":[\"film\"]}", true, (a, c) -> GameTools.onServer(s, () ->
            {
                try
                {
                    ServerPlayerEntity p = GameTools.player(s);
                    if (p == null) return Tools.err("لا يوجد لاعب متصل لتشغيل الفيلم له (التشغيل يحتاج لاعبًا).");
                    load(Json.str(a, "film", ""));
                    return runCmd(s, "bbs films play " + p.getName().getString() + " \"" + Json.str(a, "film", "") + "\" " + Json.bool(a, "camera", true));
                }
                catch (Exception e) { return Tools.err(e.getMessage()); }
            }));

        t.add("bbs_film_stop", "أوقف تشغيل فيلم.", "{\"properties\":{" + F + "},\"required\":[\"film\"]}", true, (a, c) -> GameTools.onServer(s, () ->
        {
            ServerPlayerEntity p = GameTools.player(s);
            if (p == null) return Tools.err("لا يوجد لاعب");
            return runCmd(s, "bbs films stop " + p.getName().getString() + " \"" + Json.str(a, "film", "") + "\"");
        }));

        t.add("bbs_film_delete", "احذف فيلمًا بعد نسخه احتياطيًا في مجلد النسخ.",
            "{\"properties\":{" + F + "},\"required\":[\"film\"]}", true, (a, c) -> GameTools.onServer(s, () ->
            {
                try
                {
                    String n = Json.str(a, "film", "");
                    Path file = films().getFile(n).toPath();
                    if (!Files.exists(file)) return Tools.err("غير موجود");
                    Path bk = s.dir.resolve("backups").resolve("film-" + n + "-" + System.currentTimeMillis() + ".dat");
                    Files.createDirectories(bk.getParent());
                    Files.copy(file, bk);
                    if (!films().delete(n)) return Tools.err("فشل الحذف");
                    JsonObject o = Tools.ok();
                    o.addProperty("backup", bk.toString());
                    return o;
                }
                catch (Exception e) { return Tools.err(e.getMessage()); }
            }));
    }

    static JsonObject addClip(JsonObject a) throws Exception
    {
        String n = Json.str(a, "film", "");
        Film f = load(n);
        boolean action = Json.str(a, "target", "camera").equals("action");
        String ty = Json.str(a, "type", "");
        var factory = action ? BBSMod.getFactoryActionClips() : BBSMod.getFactoryCameraClips();
        Clip clip = factory.create(Link.create(ty.contains(":") ? ty : "bbs:" + ty));
        clip.tick.set(Json.num(a, "tick", 0));
        clip.duration.set(Math.max(1, Json.num(a, "duration", 20)));
        if (a.has("title")) clip.title.set(Json.str(a, "title", ""));
        if (a.has("layer")) clip.layer.set(Json.num(a, "layer", 0));
        String patch = Json.str(a, "patch_json", "");

        if (!patch.isBlank())
        {
            MapType base = clip.toData().asMap();
            merge(base, DataToString.mapFromString(patch));
            clip.fromData(base);
            clip.tick.set(Json.num(a, "tick", 0));
            clip.duration.set(Math.max(1, Json.num(a, "duration", 20)));
        }

        if (action) replay(f, Json.str(a, "replay", "")).actions.addClip(clip);
        else f.camera.addClip(clip);

        save(n, f);
        JsonObject o = Tools.ok();
        o.addProperty("clip", ty);
        o.addProperty("film_duration_ticks", f.camera.calculateDuration());
        return o;
    }

    static JsonObject runCmd(Studio s, String command)
    {
        StringBuilder out = new StringBuilder();
        var src = s.server.getCommandSource().withOutput(new net.minecraft.server.command.CommandOutput()
        {
            public void sendMessage(net.minecraft.text.Text m) { out.append(m.getString()).append("\n"); }
            public boolean shouldReceiveFeedback() { return true; }
            public boolean shouldTrackOutput() { return true; }
            public boolean shouldBroadcastConsoleToOps() { return false; }
        });
        int[] res = {-1};
        src = src.withReturnValueConsumer((ok, v) -> res[0] = ok ? v : -1);
        s.server.getCommandManager().parseAndExecute(src, command);
        JsonObject o = res[0] >= 0 || out.length() > 0 ? Tools.ok() : Tools.err("لم ينجح الأمر (قد تحتاج صلاحيات)");
        o.addProperty("output", out.toString().trim());
        o.addProperty("result", res[0]);
        return o;
    }
}
