package com.ysm.modelengine.molang;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletionException;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YsmMigrationServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void preparesAndExecutesOnDedicatedMigrationThread() {
        Path input = temporaryDirectory.resolve("imports");
        Path output = temporaryDirectory.resolve("exports");
        Path nativeLibrary = temporaryDirectory.resolve("native/YSMParserJNI.dll");

        try (YsmMigrationService service = new YsmMigrationService(
                Logger.getAnonymousLogger(), ignored -> null,
                input, output, nativeLibrary)) {
            service.prepareAsync().join();
            String workerName = service.workerThreadNameAsync().join();

            assertTrue(Files.isDirectory(input));
            assertTrue(Files.isDirectory(output));
            assertTrue(workerName.startsWith("ysm-model-migration"));
            assertEquals(0, service.pendingJobs());
        }
    }

    @Test
    void rejectsPathTraversalInsideWorkerFuture() {
        try (YsmMigrationService service = new YsmMigrationService(
                Logger.getAnonymousLogger(), ignored -> null,
                temporaryDirectory.resolve("imports"),
                temporaryDirectory.resolve("exports"),
                temporaryDirectory.resolve("native/YSMParserJNI.dll"))) {
            CompletionException exception = assertThrows(
                    CompletionException.class,
                    () -> service.migrate("../outside.ysm").join()
            );

            Throwable cause = rootCause(exception);
            assertTrue(cause instanceof IllegalArgumentException);
            assertTrue(cause.getMessage().contains("imports"));
        }
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current;
    }
}
