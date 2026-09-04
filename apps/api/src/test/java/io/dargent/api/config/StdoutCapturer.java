package io.dargent.api.config;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Captures stdout by temporarily replacing System.out.
 * Use in tests to capture structured JSON logs written to stdout.
 */
public class StdoutCapturer {

    private final ByteArrayOutputStream baos = new ByteArrayOutputStream();
    private final PrintStream originalOut = System.out;
    private final PrintStream capturingOut;
    private boolean active = false;

    public StdoutCapturer() {
        this.capturingOut = new PrintStream(baos, true, java.nio.charset.StandardCharsets.UTF_8);
    }

    public void start() {
        if (!active) {
            System.setOut(capturingOut);
            active = true;
        }
    }

    public void stop() {
        if (active) {
            capturingOut.flush();
            System.setOut(originalOut);
            active = false;
        }
    }

    public List<String> getJsonLines() {
        String output = baos.toString(java.nio.charset.StandardCharsets.UTF_8);
        return output.lines()
                .filter(line -> line.trim().startsWith("{") && line.trim().endsWith("}"))
                .map(String::trim)
                .toList();
    }

    public String getAllOutput() {
        capturingOut.flush();
        return baos.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    public void reset() {
        baos.reset();
    }
}