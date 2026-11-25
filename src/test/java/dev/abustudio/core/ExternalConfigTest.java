package dev.abustudio.core;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class ExternalConfigTest {
 @TempDir Path dir;
 @Test void groqPresetAndFileAreLoadedWithoutEmbeddingAKey() throws Exception {
   StudioConfig c = new StudioConfig(); SecretStore secrets = new SecretStore(dir.resolve("secrets"));
   ExternalConfig.apply(dir,c,secrets);
   ProviderConfig p = c.provider("groq"); assertNotNull(p);
   assertEquals("openai/gpt-oss-20b",p.preferredModel); assertFalse(secrets.has("provider:groq"));
   Files.writeString(dir.resolve("abu-ai-studio.json"), "{\"groq_api_key\":\"REDACTED_TEST_CREDENTIAL\"}");
   ExternalConfig.apply(dir,c,secrets);
   assertEquals("REDACTED_TEST_CREDENTIAL",secrets.get("provider:groq"));
   assertEquals(1,c.providers.size());
   assertEquals("",new Router(() -> c,secrets,x -> {}).setupProblem());
 }
 @Test void missingKeyShowsSetupProblem() {
   StudioConfig c = new StudioConfig(); SecretStore secrets = new SecretStore(dir.resolve("secrets"));
   ExternalConfig.apply(dir,c,secrets);
   assertTrue(new Router(() -> c,secrets,x -> {}).setupProblem().contains("مفتاح"));
 }
}
