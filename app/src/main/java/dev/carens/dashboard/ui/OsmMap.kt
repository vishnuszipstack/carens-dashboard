package dev.carens.dashboard.ui

import android.annotation.SuppressLint
import android.graphics.Color as AColor
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.view.MotionEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.carens.dashboard.R
import dev.carens.dashboard.nav.LatLon
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/** Inverted, slightly dimmed tiles for night driving. */
internal val NightMapFilter = ColorMatrixColorFilter(
    ColorMatrix(
        floatArrayOf(
            -0.85f, 0f, 0f, 0f, 230f,
            0f, -0.85f, 0f, 0f, 235f,
            0f, 0f, -0.85f, 0f, 245f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
)

/** Lets a parent drive zoom from its own big buttons. */
class MapControls {
    internal var view: MapView? = null
    fun zoomIn() {
        view?.controller?.zoomIn()
    }

    fun zoomOut() {
        view?.controller?.zoomOut()
    }

    fun showWholeRoute(points: List<LatLon>) {
        val v = view ?: return
        if (points.size < 2) return
        val box = org.osmdroid.util.BoundingBox.fromGeoPoints(points.map { GeoPoint(it.lat, it.lon) })
        v.post { runCatching { v.zoomToBoundingBox(box.increaseByScale(1.3f), true) } }
    }
}

/**
 * OpenStreetMap (osmdroid) with the car's position, an optional route line,
 * destination and parking markers. Following/heading-up are controlled by the
 * parent; any touch calls [onUserMoved] so the parent can pause following.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun OsmMap(
    position: LatLon?,
    bearing: Float?,
    dark: Boolean,
    follow: Boolean,
    headingUp: Boolean,
    modifier: Modifier = Modifier,
    route: List<LatLon> = emptyList(),
    destination: LatLon? = null,
    parking: LatLon? = null,
    zoom: Double = 16.5,
    controls: MapControls? = null,
    onUserMoved: () -> Unit = {},
    onLongPress: ((LatLon) -> Unit)? = null,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val holder = remember { MapHolder() }
    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            minZoomLevel = 4.0
            maxZoomLevel = 19.0
            controller.setZoom(zoom)
            isVerticalMapRepetitionEnabled = false
        }
    }
    controls?.view = mapView

    val routeLine = remember {
        Polyline(mapView).apply {
            outlinePaint.color = AColor.parseColor("#2F80ED")
            outlinePaint.strokeWidth = 14f
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
            isEnabled = false
        }
    }
    val destMarker = remember {
        Marker(mapView).apply {
            icon = ContextCompat.getDrawable(context, R.drawable.ic_pin)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            setInfoWindow(null)
            isEnabled = false
        }
    }
    val parkMarker = remember {
        Marker(mapView).apply {
            icon = ContextCompat.getDrawable(context, R.drawable.ic_parking)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            setInfoWindow(null)
            isEnabled = false
        }
    }

    DisposableEffect(mapView) {
        mapView.overlays.add(routeLine)
        mapView.overlays.add(parkMarker)
        mapView.overlays.add(destMarker)
        mapView.overlays.add(
            0,
            MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false
                override fun longPressHelper(p: GeoPoint?): Boolean {
                    val cb = holder.onLongPress ?: return false
                    p?.let { cb(LatLon(it.latitude, it.longitude)) }
                    return true
                }
            }),
        )
        mapView.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_MOVE || ev.pointerCount > 1) holder.onUserMoved()
            false
        }
        onDispose { }
    }
    holder.onLongPress = onLongPress
    holder.onUserMoved = onUserMoved

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (controls?.view === mapView) controls.view = null
            mapView.onDetach()
        }
    }

    Box(modifier) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
            update = { mv ->
                mv.overlayManager.tilesOverlay.setColorFilter(if (dark) NightMapFilter else null)

                if (route !== holder.route) {
                    holder.route = route
                    routeLine.setPoints(route.map { GeoPoint(it.lat, it.lon) })
                    routeLine.isEnabled = route.size >= 2
                }
                destMarker.isEnabled = destination != null
                destination?.let { destMarker.position = GeoPoint(it.lat, it.lon) }
                parkMarker.isEnabled = parking != null
                parking?.let { parkMarker.position = GeoPoint(it.lat, it.lon) }

                val heading = if (headingUp && follow && bearing != null) -bearing else 0f
                if (mv.mapOrientation != heading) mv.mapOrientation = heading

                if (follow && position != null) {
                    mv.controller.animateTo(GeoPoint(position.lat, position.lon))
                }
                mv.invalidate()
            },
        )
        if (position != null && follow) {
            // Arrow drawn on top: points up in heading-up mode, else along the bearing.
            val rotation = if (headingUp && bearing != null) 0f else (bearing ?: 0f)
            Image(
                painter = painterResource(R.drawable.ic_nav_arrow),
                contentDescription = "Your position",
                modifier = Modifier.align(Alignment.Center).size(40.dp).rotate(rotation),
            )
        }
    }
}

private class MapHolder {
    var route: List<LatLon>? = null
    var onLongPress: ((LatLon) -> Unit)? = null
    var onUserMoved: () -> Unit = {}
}
