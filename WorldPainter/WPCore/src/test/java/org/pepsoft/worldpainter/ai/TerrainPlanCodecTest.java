package org.pepsoft.worldpainter.ai;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.Assert.*;

public class TerrainPlanCodecTest {
    @Test
    public void offlineGrammarPreservesNegativeCoordinatesAndSeed() {
        TerrainPlan expected = new TerrainPlan(List.of(new TerrainCommand.Sculpt(
                new EditRegion(-96, -160, 129, 129), TerrainCommand.Shape.MOUNTAIN, 40, -42, 0)));

        assertEquals(expected, TerrainPlanCodec.parseOffline("  Create a mountain at -32, -96 radius 64 height 40 seed -42  "));
        assertEquals(expected, TerrainPlanCodec.parseOffline("CREATE A MOUNTAIN AT -32 -96 RADIUS 64 HEIGHT 40 SEED -42"));
    }

    @Test
    public void offlineGrammarSupportsEverySculptShape() {
        for (TerrainCommand.Shape shape : TerrainCommand.Shape.values()) {
            boolean water = shape == TerrainCommand.Shape.RIVER || shape == TerrainCommand.Shape.ISLAND
                    || shape == TerrainCommand.Shape.CONTINENT;
            String input = "Create a " + shape.name().toLowerCase(Locale.ROOT)
                    + " at 0, 0 radius 8 height 12" + (water ? " water -16" : "");
            TerrainCommand.Sculpt command = (TerrainCommand.Sculpt) TerrainPlanCodec.parseOffline(input).commands().get(0);

            assertEquals(shape, command.shape());
            assertEquals(new EditRegion(-8, -8, 17, 17), command.region());
            assertEquals(12, command.magnitude());
            assertEquals(0L, command.seed());
            assertEquals(water ? -16 : 0, command.waterLevel());
        }
    }

    @Test
    public void offlineWaterShapesRequireAnExplicitLevel() {
        for (String shape : List.of("river", "island", "continent")) {
            assertThrows(IllegalArgumentException.class, () -> TerrainPlanCodec.parseOffline(
                    "Create a " + shape + " at 0, 0 radius 8 height 12 seed 1"));
        }
    }

    @Test
    public void offlineDryShapesRejectUnusedWaterLevels() {
        for (String shape : List.of("hill", "mountain", "valley")) {
            assertThrows(IllegalArgumentException.class, () -> TerrainPlanCodec.parseOffline(
                    "Create a " + shape + " at 0, 0 radius 8 height 12 seed 1 water 63"));
        }
    }

    @Test
    public void offlineInputRejectsUnsupportedGrammarAndUnsafeNumbers() {
        for (String input : Arrays.asList(null, "", "Create a volcano at 0, 0 radius 8 height 12",
                "Create a mountain at 0, 0 radius 8 height 12; run a script",
                "Create a mountain at 0, 0 radius 0 height 12",
                "Create a mountain at 0, 0 radius 129 height 12",
                "Create a mountain at 0, 0 radius 8 height 0",
                "Create a mountain at 0, 0 radius 8 height 257",
                "Create a mountain at 0, 0 radius 8 height 1.5",
                "Create a mountain at -2147483648, 0 radius 8 height 12",
                "Create a mountain at 2147483647, 0 radius 8 height 12",
                "Create a mountain at 0, 2147483648 radius 8 height 12",
                "Create a mountain at 0, 0 radius 8 height 12 seed 9223372036854775808")) {
            assertThrows("Accepted: " + input, IllegalArgumentException.class, () -> TerrainPlanCodec.parseOffline(input));
        }
    }

    @Test
    public void jsonSupportsMixedSculptBiomeAndLayerCommands() {
        String input = "{\"version\":1,\"commands\":["
                + sculpt("river", "\"waterLevel\":63,") + ","
                + "{\"type\":\"biome\",\"region\":" + REGION + ",\"biomeId\":1},"
                + "{\"type\":\"layer\",\"region\":" + REGION + ",\"layerId\":\"custom-layer-id\",\"value\":15}]}";
        TerrainPlan expected = new TerrainPlan(List.of(
                new TerrainCommand.Sculpt(new EditRegion(-10, 20, 4, 8), TerrainCommand.Shape.RIVER, 40, -42, 63),
                new TerrainCommand.PaintBiome(new EditRegion(-10, 20, 4, 8), 1),
                new TerrainCommand.PaintLayer(new EditRegion(-10, 20, 4, 8), "custom-layer-id", 15)));

        assertEquals(expected, TerrainPlanCodec.parse(input));
        assertEquals(expected, TerrainPlanCodec.parseOffline("\n  " + input));
    }

    @Test
    public void jsonSupportsEverySculptShapeAndFullWidthSeeds() {
        for (TerrainCommand.Shape shape : TerrainCommand.Shape.values()) {
            boolean water = shape == TerrainCommand.Shape.RIVER || shape == TerrainCommand.Shape.ISLAND
                    || shape == TerrainCommand.Shape.CONTINENT;
            String input = plan(sculpt(shape.name().toLowerCase(Locale.ROOT), water ? "\"waterLevel\":63," : ""));
            TerrainCommand.Sculpt actual = (TerrainCommand.Sculpt) TerrainPlanCodec.parse(input).commands().get(0);
            assertEquals(shape, actual.shape());
            for (long seed : new long[]{Long.MIN_VALUE, Long.MAX_VALUE}) {
                TerrainCommand.Sculpt seeded = (TerrainCommand.Sculpt) TerrainPlanCodec.parse(
                        input.replace("\"seed\":-42", "\"seed\":" + seed)).commands().get(0);
                assertEquals(seed, seeded.seed());
            }
        }
    }

    @Test
    public void encodingRoundTripsEveryCommandWithoutLosingParameters() {
        List<TerrainCommand> commands = new ArrayList<>();
        EditRegion region = new EditRegion(-10, 20, 4, 8);
        for (TerrainCommand.Shape shape : TerrainCommand.Shape.values()) {
            boolean water = shape == TerrainCommand.Shape.RIVER || shape == TerrainCommand.Shape.ISLAND
                    || shape == TerrainCommand.Shape.CONTINENT;
            commands.add(new TerrainCommand.Sculpt(region, shape, 256, Long.MIN_VALUE, water ? -16 : 0));
        }
        commands.add(new TerrainCommand.PaintBiome(region, 255));
        commands.add(new TerrainCommand.PaintLayer(region, "id with \"quotes\" and Unicode 雪", 255));
        TerrainPlan original = new TerrainPlan(commands);

        assertEquals(original, TerrainPlanCodec.parse(TerrainPlanCodec.encode(original)));
        assertEquals(original, TerrainPlanCodec.parseOffline(TerrainPlanCodec.encode(original)));
    }

    @Test
    public void jsonRequiresExactFieldsAtEveryLevel() {
        String valid = plan(sculpt("mountain", ""));
        for (String input : List.of(
                valid.replace("\"version\":1,", ""),
                valid.replace("\"commands\":", "\"extra\":true,\"commands\":"),
                valid.replace("\"seed\":-42,", ""),
                valid.replace("\"seed\":-42,", "\"seed\":-42,\"script\":\"run()\","),
                valid.replace("\"height\":8", "\"height\":8,\"z\":0"),
                valid.replace("\"x\":-10,", ""),
                valid.replace("\"seed\":-42,", "\"seed\":-42,\"waterLevel\":63,"),
                plan(sculpt("river", "")),
                plan("{\"type\":\"biome\",\"region\":" + REGION + ",\"biomeId\":1,\"value\":7}"),
                plan("{\"type\":\"layer\",\"region\":" + REGION + ",\"layerId\":\"test\"}"))) {
            reject(input);
        }
    }

    @Test
    public void jsonRejectsDuplicateFieldsIncludingNestedObjects() {
        String valid = plan(sculpt("mountain", ""));
        reject(valid.replace("\"version\":1", "\"version\":1,\"version\":1"));
        reject(valid.replace("\"seed\":-42", "\"seed\":-42,\"seed\":2"));
        reject(valid.replace("\"x\":-10", "\"x\":-10,\"x\":5"));
    }

    @Test
    public void jsonRejectsMalformedSyntaxAndParserResourceAbuse() {
        String command = sculpt("mountain", "");
        String valid = plan(command);
        Map<String, String> invalid = Map.ofEntries(
                Map.entry("missing object comma", valid.replace("\"version\":1,", "\"version\":1 ")),
                Map.entry("trailing object comma", valid.replace("\"magnitude\":40}", "\"magnitude\":40,}")),
                Map.entry("missing array comma", plan(command + " " + command)),
                Map.entry("trailing array comma", plan(command + ",")),
                Map.entry("comment", valid.replace("\"version\":1", "\"version\":/* comment */1")),
                Map.entry("single quotes", valid.replace('"', '\'')),
                Map.entry("multiple root values", valid + " " + valid),
                Map.entry("extreme nesting", "{\"version\":1,\"commands\":"
                        + "[".repeat(4096) + "0" + "]".repeat(4096) + "}"),
                Map.entry("huge numeric token", valid.replace("\"seed\":-42", "\"seed\":" + "9".repeat(10_000))));

        for (Map.Entry<String, String> input : invalid.entrySet()) {
            assertThrows(input.getKey(), IllegalArgumentException.class, () -> TerrainPlanCodec.parse(input.getValue()));
        }
    }

    @Test
    public void jsonRejectsUnsupportedTypesVersionsAndNonPlanText() {
        String valid = plan(sculpt("mountain", ""));
        for (String input : List.of(valid.replace("\"mountain\"", "\"script\""),
                valid.replace("\"version\":1", "\"version\":2"),
                "```json\n" + valid + "\n```", "Here is your plan: " + valid,
                valid + " trailing text", "[]", "null", "{}")) {
            reject(input);
        }
    }

    @Test
    public void jsonRejectsFractionalCoercedAndOutOfRangeNumbers() {
        String valid = plan(sculpt("mountain", ""));
        for (String input : List.of(valid.replace("\"version\":1", "\"version\":1.0"),
                valid.replace("\"width\":4", "\"width\":4.5"),
                valid.replace("\"magnitude\":40", "\"magnitude\":40.0"),
                valid.replace("\"seed\":-42", "\"seed\":1e3"),
                valid.replace("\"width\":4", "\"width\":\"4\""),
                valid.replace("\"x\":-10", "\"x\":true"),
                valid.replace("\"x\":-10", "\"x\":2147483648"),
                valid.replace("\"y\":20", "\"y\":-2147483649"),
                valid.replace("\"seed\":-42", "\"seed\":9223372036854775808"),
                valid.replace("\"magnitude\":40", "\"magnitude\":0"),
                valid.replace("\"magnitude\":40", "\"magnitude\":257"))) {
            reject(input);
        }
    }

    @Test
    public void jsonRejectsNullValuesAndWrongContainers() {
        String valid = plan(sculpt("mountain", ""));
        reject(null);
        for (String input : List.of("{\"version\":1,\"commands\":null}",
                "{\"version\":1,\"commands\":{}}", "{\"version\":1,\"commands\":[null]}",
                valid.replace("\"version\":1", "\"version\":null"),
                valid.replace("\"mountain\"", "null"), valid.replace(REGION, "null"),
                valid.replace(REGION, "[]"), valid.replace("\"x\":-10", "\"x\":null"),
                valid.replace("\"seed\":-42", "\"seed\":null"),
                plan("{\"type\":\"layer\",\"region\":" + REGION + ",\"layerId\":null,\"value\":1}"))) {
            reject(input);
        }
    }

    @Test
    public void jsonRejectsInvalidBiomeAndLayerParameters() {
        for (String biome : List.of("-1", "256", "1.5", "null")) {
            reject(plan("{\"type\":\"biome\",\"region\":" + REGION + ",\"biomeId\":" + biome + "}"));
        }
        for (String layer : List.of("", " ", "x".repeat(257))) {
            reject(plan("{\"type\":\"layer\",\"region\":" + REGION + ",\"layerId\":\"" + layer + "\",\"value\":1}"));
        }
        for (String value : List.of("-1", "256", "null")) {
            reject(plan("{\"type\":\"layer\",\"region\":" + REGION + ",\"layerId\":\"test\",\"value\":" + value + "}"));
        }
    }

    @Test
    public void jsonEnforcesInputCommandAndAggregateColumnBudgets() {
        reject(" ".repeat(65_537));
        reject("{\"version\":1,\"commands\":[]}");
        reject(plan(String.join(",", Collections.nCopies(9, sculpt("mountain", "")))));
        String fullRegion = "{\"x\":0,\"y\":0,\"width\":1024,\"height\":256}";
        String fullCommand = sculpt("mountain", "").replace(REGION, fullRegion);
        assertEquals(EditRegion.MAX_COLUMNS, TerrainPlanCodec.parse(plan(fullCommand)).commands().get(0).region().columns());
        reject(plan(fullCommand + "," + sculpt("mountain", "")));
    }

    @Test
    public void regionsAllowBoundaryCoordinatesWithoutOverflow() {
        assertEquals(1, new EditRegion(Integer.MAX_VALUE, Integer.MAX_VALUE, 1, 1).columns());
        assertEquals(32, new EditRegion(Integer.MIN_VALUE, Integer.MIN_VALUE, 4, 8).columns());
        assertEquals(EditRegion.MAX_COLUMNS, new EditRegion(-512, -128, 1024, 256).columns());
        new EditRegion(Integer.MAX_VALUE - 3, Integer.MAX_VALUE - 7, 4, 8);

        assertThrows(IllegalArgumentException.class, () -> new EditRegion(Integer.MAX_VALUE - 2, 0, 4, 8));
        assertThrows(IllegalArgumentException.class, () -> new EditRegion(0, Integer.MAX_VALUE - 6, 4, 8));
    }

    @Test
    public void jsonRejectsExcessiveOrEmptyRegionsBeforeEditing() {
        String valid = plan(sculpt("mountain", ""));
        for (String region : List.of("{\"x\":0,\"y\":0,\"width\":0,\"height\":1}",
                "{\"x\":0,\"y\":0,\"width\":1,\"height\":-1}",
                "{\"x\":0,\"y\":0,\"width\":1025,\"height\":1}",
                "{\"x\":0,\"y\":0,\"width\":1024,\"height\":257}",
                "{\"x\":0,\"y\":0,\"width\":2147483647,\"height\":2147483647}",
                "{\"x\":2147483647,\"y\":0,\"width\":2,\"height\":1}",
                "{\"x\":0,\"y\":2147483647,\"width\":1,\"height\":2}")) {
            reject(valid.replace(REGION, region));
        }
    }

    @Test
    public void terrainPlansDefensivelyCopyAndExposeImmutableCommands() {
        TerrainCommand command = new TerrainCommand.PaintBiome(new EditRegion(0, 0, 1, 1), 1);
        List<TerrainCommand> source = new ArrayList<>(List.of(command));
        TerrainPlan plan = new TerrainPlan(source);
        source.clear();

        assertEquals(List.of(command), plan.commands());
        assertThrows(UnsupportedOperationException.class, () -> plan.commands().clear());
        assertThrows(NullPointerException.class, () -> new TerrainPlan(null));
        assertThrows(NullPointerException.class, () -> new TerrainPlan(Arrays.asList(command, null)));
        assertThrows(IllegalArgumentException.class, () -> new TerrainPlan(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new TerrainPlan(Collections.nCopies(9, command)));
    }

    @Test
    public void terrainPlansEnforceTheTotalBudgetAcrossAllCommands() {
        TerrainCommand command = new TerrainCommand.PaintBiome(new EditRegion(0, 0, 256, 128), 1);
        assertEquals(8, new TerrainPlan(Collections.nCopies(8, command)).commands().size());
        TerrainCommand fullRegion = new TerrainCommand.PaintBiome(new EditRegion(0, 0, 1024, 256), 1);
        assertThrows(IllegalArgumentException.class, () -> new TerrainPlan(List.of(fullRegion, command)));
    }

    private static String sculpt(String shape, String additionalFields) {
        return "{\"type\":\"" + shape + "\",\"region\":" + REGION
                + ",\"seed\":-42," + additionalFields + "\"magnitude\":40}";
    }

    private static String plan(String commands) {
        return "{\"version\":1,\"commands\":[" + commands + "]}";
    }

    private static void reject(String input) {
        assertThrows("Accepted: " + input, IllegalArgumentException.class, () -> TerrainPlanCodec.parse(input));
    }

    private static final String REGION = "{\"x\":-10,\"y\":20,\"width\":4,\"height\":8}";
}
