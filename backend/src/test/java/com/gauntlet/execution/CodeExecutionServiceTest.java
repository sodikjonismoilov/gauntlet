package com.gauntlet.execution;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plain unit test — no Spring context, no database. Exercises the real Docker
 * sandbox, so Docker must be running and the language images pullable.
 */
class CodeExecutionServiceTest {

    private final CodeExecutionService service = new CodeExecutionService();

    @Test
    void pythonEchoesStdin() throws Exception {
        String src = "print('hello ' + input())";
        var result = service.run(CodeExecutionService.Language.PYTHON, src, "world");

        assertEquals(0, result.exitCode(), "stderr was: " + result.stderr());
        assertFalse(result.timedOut());
        assertEquals("hello world", result.stdout().strip());
    }
}