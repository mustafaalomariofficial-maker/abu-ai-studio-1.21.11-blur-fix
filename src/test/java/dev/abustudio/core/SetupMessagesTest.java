package dev.abustudio.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SetupMessagesTest
{
    @Test
    void noProviderExplanationIsArabicAndActionable()
    {
        String msg = SetupMessages.help("لا يوجد مزود مفعّل.");
        assertTrue(msg.contains("لا يوجد مزود"), msg);
        assertTrue(msg.contains("config/abu-ai-studio.json"), msg);
        assertTrue(msg.contains("Groq"), msg);
        assertTrue(msg.contains("K"), msg);
    }

    @Test
    void routerReportsMissingSetupInArabic()
    {
        StudioConfig cfg = new StudioConfig();
        java.nio.file.Path dir;
        try { dir = java.nio.file.Files.createTempDirectory("abu-setup"); } catch (java.io.IOException e) { throw new RuntimeException(e); }
        Router router = new Router(() -> cfg, new SecretStore(dir), t -> {});
        String problem = router.setupProblem();
        assertTrue(problem.contains("مزود"), problem);
    }
}
