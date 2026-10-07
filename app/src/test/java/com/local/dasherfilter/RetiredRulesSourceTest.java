package com.local.dasherfilter;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 0.5.0 retired score by area, the adaptive minimum with everything it learned (accepted bests, decline lessons, the
 * held hand-decline record) and the hotspot, per-stop and per-item minimums. Their inert compatibility surface (constant
 * fields, no-op or throwing methods, the old positional constructors, the learning stubs and the old area score) is
 * gone too, and this suite keeps it gone: no main source names any of it, FilterSettings has only its 0.5.0
 * constructor, and the retired preference keys are named only where the migration removes them. The retired axes keep
 * their reserved slots, which refuse any value rather than change its meaning.
 */
public final class RetiredRulesSourceTest {
    /** The retired rules' members the release gate names. */
    private static final List<String> RETIRED = Arrays.asList("scoreByArea", "risingOffers", "adoptAdaptive",
            "ManualDeclines", "hotspotProximityHundredths", "perStopCents", "perItemCents");

    /** The rest of the inert surface removed with them. */
    private static final List<String> REMOVED = Arrays.asList("withScoreByArea", "withAdaptive",
            "withoutRisingBaseline", "withHotspotProximity", "withPerItem", "proximityLabel", "lastAcceptedCents",
            "AcceptedBest", "DeclinedFloor", "learnFromDecline", "recordAccepted", "recordAcceptedLesson",
            "resetAccepted", "learningTimes", "DeclineLesson", "AcceptedLesson", "onlyHotspotMissing", "scoreReason",
            "SCORE_REASON", "scoreToggleBox", "incrementalFloors", "requiredPay");

    private static final String PACKAGE = "app/src/main/java/com/local/dasherfilter/";

    @Test
    public void noMainSourceNamesARetiredRule() throws IOException {
        assertEquals(Arrays.asList(), mentions(RETIRED));
    }

    @Test
    public void noMainSourceNamesTheRemovedCompatibilitySurface() throws IOException {
        assertEquals(Arrays.asList(), mentions(REMOVED));
        // The old area score's Floors went with it ("floors" is too plain a word to look for in the app's prose).
        List<String> nested = new ArrayList<>();
        for (Class<?> type : Arrays.asList(AreaScore.class, FilterStore.class, FilterSettings.class)) {
            for (Class<?> member : type.getDeclaredClasses()) nested.add(member.getSimpleName());
        }
        for (String removed : Arrays.asList("Floors", "DeclineLesson", "AcceptedLesson")) {
            assertFalse(removed + " in " + nested, nested.contains(removed));
        }
    }

    @Test
    public void filterSettingsHasOnlyItsZeroFiveConstructorAndMainCallsNoOther() throws IOException {
        Constructor<?>[] constructors = FilterSettings.class.getDeclaredConstructors();
        assertEquals("no legacy positional constructor", 1, constructors.length);
        assertArrayEquals(new Class<?>[] {boolean.class, int.class, int.class, int.class, int.class, boolean.class,
                int.class, int.class}, constructors[0].getParameterTypes());
        // Every call in the app passes those eight values: enabled, three minimums, max stops, Autopilot, goal, bar.
        List<String> calls = new ArrayList<>();
        for (Path source : mainSources()) {
            String text = read(source);
            Matcher call = Pattern.compile("new FilterSettings\\(").matcher(text);
            while (call.find()) {
                List<String> args = arguments(text, call.end());
                if (args.size() != 8) calls.add(source.getFileName() + ": " + args);
            }
        }
        assertEquals(Arrays.asList(), calls);
    }

    @Test
    public void theRetiredKeysAreNamedOnlyWhereTheMigrationRemovesThem() throws IOException {
        assertEquals(25, FilterStore.RETIRED_KEYS.size());
        List<String> found = new ArrayList<>();
        for (Path source : mainSources()) {
            if (source.getFileName().toString().equals("FilterStore.java")) continue;
            String text = read(source);
            for (String key : FilterStore.RETIRED_KEYS) {
                if (text.contains("\"" + key + "\"")) found.add(source.getFileName() + ": " + key);
            }
            if (text.contains("\"" + FilterStore.RETIRED_MANUAL_DECLINES + "\"")) {
                found.add(source.getFileName() + ": " + FilterStore.RETIRED_MANUAL_DECLINES);
            }
        }
        assertEquals(Arrays.asList(), found);
        // The shells of what was learned are gone with the learning.
        for (String name : Arrays.asList("AcceptedBest", "DeclinedFloor", "ManualDeclines")) {
            assertFalse(name, new File(root(), PACKAGE + name + ".java").exists());
            try {
                Class.forName("com.local.dasherfilter." + name);
                fail(name + " still exists");
            } catch (ClassNotFoundException expected) {
                // Gone.
            }
        }
    }

    @Test
    public void theRetiredSlotsStayReservedAndRefuseAnyValue() {
        FilterSettings rules = FilterSettings.of(true, 1300, 385, 41, 3).withMinimumScalePercent(90);
        assertArrayEquals("pay, per mile, per minute, then the reserved stop, hotspot and item slots",
                new int[] {1300, 385, 41, 0, 0, 0}, rules.minimums());
        refused("per stop", () -> rules.withMinimums(new int[] {1300, 385, 41, 475}));
        refused("hotspot", () -> rules.withMinimums(new int[] {1300, 385, 41, 0, 50}));
        refused("per item", () -> rules.withMinimums(new int[] {1300, 385, 41, 0, 0, 735}));
        refused("per stop", () -> rules.withMinimums(new int[] {1300, 385, 41, -1}));
        for (int[] rates : new int[][] {{1400, 400, 45, 0, 0, 0}, {1400, 400, 45, 0}, {1400, 400, 45}}) {
            FilterSettings changed = rules.withMinimums(rates);
            assertArrayEquals(new int[] {1400, 400, 45, 0, 0, 0}, changed.minimums());
            assertEquals("the rest stays", rules.withMinimums(1400, 400, 45).rulesKey(), changed.rulesKey());
            assertEquals(90, changed.minimumScalePercent);
            assertEquals(3, changed.maxStops);
        }
    }

    private interface Build {
        FilterSettings run();
    }

    private static void refused(String rule, Build build) {
        try {
            build.run();
            fail("expected retired rule: " + rule);
        } catch (IllegalArgumentException expected) {
            assertEquals("retired rule: " + rule, expected.getMessage());
        }
    }

    /** Each whole-word mention of {@code names} in the app's sources and resources, as "File.java:line name". */
    private static List<String> mentions(List<String> names) throws IOException {
        List<Pattern> words = names.stream().map(name -> Pattern.compile("\\b" + Pattern.quote(name) + "\\b"))
                .collect(Collectors.toList());
        List<String> found = new ArrayList<>();
        for (Path source : mainSources()) {
            List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
            for (int n = 0; n < lines.size(); n++) {
                for (int w = 0; w < words.size(); w++) {
                    if (words.get(w).matcher(lines.get(n)).find()) {
                        found.add(source.getFileName() + ":" + (n + 1) + " " + names.get(w));
                    }
                }
            }
        }
        return found;
    }

    /** Every Java source and XML file of the app (the manifest and resources). */
    private static List<Path> mainSources() throws IOException {
        File main = new File(root(), "app/src/main");
        assertTrue("the app's sources are where this suite looks", new File(main, PACKAGE.substring(
                "app/src/main/".length()) + "FilterSettings.java").isFile());
        try (Stream<Path> files = Files.walk(main.toPath())) {
            List<Path> sources = files.filter(path -> path.toString().endsWith(".java")
                    || path.toString().endsWith(".xml")).sorted().collect(Collectors.toList());
            assertTrue("found the app's sources: " + sources.size(), sources.size() > 100);
            return sources;
        }
    }

    private static String read(Path source) throws IOException {
        return new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
    }

    /** The top-level arguments of a call whose "(" ends at {@code start}, trimmed. */
    private static List<String> arguments(String text, int start) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        char quote = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                current.append(c);
                if (c == '\\' && i + 1 < text.length()) current.append(text.charAt(++i));
                else if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c;
                current.append(c);
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
                current.append(c);
            } else if ((c == ')' || c == ']' || c == '}') && depth > 0) {
                depth--;
                current.append(c);
            } else if (c == ')') {
                args.add(current.toString().trim());
                return args;
            } else if (c == ',' && depth == 0) {
                args.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        throw new AssertionError("an unclosed call at " + start);
    }

    /** The repository's root: the tests run from app/ (Gradle) or from the root. */
    private static File root() {
        return new File("../app").isDirectory() ? new File("..") : new File(".");
    }
}
