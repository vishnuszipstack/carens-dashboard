package dev.carens.dashboard.nav

import dev.carens.dashboard.fuel.FuelEntry
import dev.carens.dashboard.fuel.FuelMath
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteParseTest {

    private val osrm = """
        {"distance": 1520.5, "duration": 240.2,
         "geometry": {"coordinates": [[76.2140,10.5270],[76.2150,10.5275],[76.2160,10.5290]]},
         "legs": [{"steps": [
            {"distance": 400, "duration": 60, "name": "MG Road",
             "maneuver": {"type": "depart", "modifier": "", "location": [76.2140,10.5270]}},
            {"distance": 900, "duration": 140, "name": "Round South",
             "maneuver": {"type": "turn", "modifier": "left", "location": [76.2150,10.5275]}},
            {"distance": 0, "duration": 0, "name": "",
             "maneuver": {"type": "roundabout", "modifier": "right", "exit": 2, "location": [76.2160,10.5290]}},
            {"distance": 0, "duration": 0, "name": "",
             "maneuver": {"type": "arrive", "modifier": "", "location": [76.2160,10.5290]}}
         ]}]}
    """.trimIndent()

    @Test
    fun parsesGeometryStepsAndInstructions() {
        val r = RouteService.parseRoute(JSONObject(osrm))
        assertEquals(3, r.points.size)
        assertEquals(10.5270, r.points[0].lat, 1e-9) // GeoJSON is [lon, lat]
        assertEquals(4, r.steps.size)
        assertEquals(Maneuver.DEPART, r.steps[0].maneuver)
        assertEquals("Head out on MG Road", r.steps[0].instruction)
        assertEquals(Maneuver.LEFT, r.steps[1].maneuver)
        assertEquals("Turn left onto Round South", r.steps[1].instruction)
        assertEquals("At the roundabout, take exit 2", r.steps[2].instruction)
        assertEquals(Maneuver.ARRIVE, r.steps[3].maneuver)
    }

    @Test
    fun pointToRouteDistance() {
        val a = LatLon(10.0, 76.0)
        val b = LatLon(10.0, 76.01) // ~1.1 km east
        val onLine = LatLon(10.0, 76.005)
        val off = LatLon(10.001, 76.005) // ~111 m north
        assertTrue(Geo.distanceToSegment(onLine, a, b) < 1.0)
        assertEquals(111.3, Geo.distanceToSegment(off, a, b), 1.0)
    }

    @Test
    fun navDistanceRounding() {
        assertEquals("80 m", navDistance(84.0, false))
        assertEquals("350 m", navDistance(372.0, false))
        assertEquals("1.2 km", navDistance(1240.0, false))
        assertEquals("12 km", navDistance(12_400.0, false))
    }

    @Test
    fun fuelFullToFull() {
        val e = listOf(
            FuelEntry(1, 1, 10_000.0, 40.0, 4000.0, true),
            FuelEntry(2, 2, 10_250.0, 10.0, 1000.0, false),
            FuelEntry(3, 3, 10_600.0, 30.0, 3000.0, true),
        )
        val s = FuelMath.summarize(e)
        // 600 km on 10 + 30 litres = 15 km/l
        assertEquals(1, s.intervals.size)
        assertEquals(15.0, s.averageKmPerLitre!!, 1e-9)
        assertEquals(4000.0 / 600.0, s.averageCostPerKm!!, 1e-9)
    }
}
