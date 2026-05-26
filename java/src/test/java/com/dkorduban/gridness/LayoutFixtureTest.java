package com.dkorduban.gridness;

import com.dkorduban.gridness.fixture.LayoutFixture;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LayoutFixtureTest {

    @Test
    void loadsGridUniform256() throws Exception {
        Path dir = LayoutFixture.defaultDir();
        LayoutFixture fx = LayoutFixture.load(dir, "grid_uniform_256");
        assertEquals(256, fx.width);
        assertEquals(256, fx.height);
        assertFalse(fx.buildings.isEmpty(), "should have building list");
        // Every cell in every building should be a wall in the raster.
        for (int[][] b : fx.buildings) {
            for (int[] xy : b) {
                assertTrue(fx.raster[xy[1]][xy[0]],
                        "building cell (" + xy[0] + "," + xy[1] + ") is not a wall");
            }
        }
    }

    @Test
    void loadsLonghouseFixtures() throws Exception {
        Path dir = LayoutFixture.defaultDir();
        for (String name : new String[]{
                "longhouses_12x60", "longhouses_22x60",
                "longhouses_12x100", "longhouses_22x100"}) {
            LayoutFixture fx = LayoutFixture.load(dir, name);
            assertTrue(fx.buildings.size() >= 4, name + ": expected multiple buildings");
        }
    }

    @Test
    void loads768Fixtures() throws Exception {
        Path dir = LayoutFixture.defaultDir();
        for (String name : new String[]{"grid_768", "city_768"}) {
            LayoutFixture fx = LayoutFixture.load(dir, name);
            assertEquals(768, fx.width);
            assertEquals(768, fx.height);
        }
    }
}
