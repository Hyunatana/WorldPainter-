package org.pepsoft.worldpainter.ai;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TerrainPromptTest {
    @Test public void suppliedExampleIsAcceptedByTheCommandValidator() {
        String example = TerrainPrompt.SYSTEM.substring(TerrainPrompt.SYSTEM.indexOf("Example response:\n") + "Example response:\n".length()).trim();
        TerrainPlan plan = TerrainPlanCodec.parse(example);
        assertEquals(1, plan.commands().size());
        assertEquals(TerrainCommand.Shape.MOUNTAIN, ((TerrainCommand.Sculpt) plan.commands().get(0)).shape());
    }

    @Test public void instructionCoversEachSupportedShapeAndPaintOperation() {
        for (TerrainCommand.Shape shape : TerrainCommand.Shape.values()) {
            assertTrue(TerrainPrompt.SYSTEM.contains("\"type\":\"" + shape.name().toLowerCase(java.util.Locale.ROOT) + "\""));
        }
        assertTrue(TerrainPrompt.SYSTEM.contains("\"type\":\"biome\""));
        assertTrue(TerrainPrompt.SYSTEM.contains("\"type\":\"layer\""));
        assertTrue(TerrainPrompt.SYSTEM.contains("262144"));
        assertTrue(TerrainPrompt.SYSTEM.contains("1 to 8 commands"));
    }
}
