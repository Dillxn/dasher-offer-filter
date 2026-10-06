package com.local.dasherfilter;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Which nodes are map views whose children a read leaves unread, and which words show a sign of an offer. */
public class MapNodesTest {
    @Test
    public void mapViewsByClassName() {
        assertTrue(MapNodes.isMap("com.google.android.gms.maps.MapView", null, null));
        assertTrue(MapNodes.isMap("com.mapbox.maps.MapView", null, null));
        assertTrue(MapNodes.isMap("com.mapbox.mapboxsdk.maps.MapView", null, null));
        assertFalse(MapNodes.isMap("android.widget.FrameLayout", null, null));
        assertFalse(MapNodes.isMap("com.example.MapViewModelHolder", null, null));
    }

    @Test
    public void surfacesAndTexturesOnlyWhenTheirViewIdNamesAMap() {
        assertTrue(MapNodes.isMap("android.view.SurfaceView", "com.doordash.driverapp:id/map_view", null));
        assertTrue(MapNodes.isMap("android.view.TextureView", "com.doordash.driverapp:id/googleMap", null));
        assertTrue(MapNodes.isMap("android.view.SurfaceView", "com.doordash.driverapp:id/nav_map", null));
        assertTrue(MapNodes.isMap("android.view.TextureView", "mapbox_surface", null));
        assertFalse("not a bitmap", MapNodes.isMap("android.view.TextureView", "com.doordash.driverapp:id/bitmap", null));
        assertFalse(MapNodes.isMap("android.view.SurfaceView", "com.doordash.driverapp:id/heatmap_layer", null));
        assertFalse(MapNodes.isMap("android.view.SurfaceView", "com.doordash.driverapp:id/camera_preview", null));
        assertFalse("no view ID, no map", MapNodes.isMap("android.view.SurfaceView", null, null));
        assertFalse("an ordinary view with a map ID", MapNodes.isMap("android.widget.Button", "id/map_button", null));
    }

    @Test
    public void mapsByTheirDescription() {
        assertTrue(MapNodes.isMap("android.view.View", null, "Google Map"));
        assertTrue(MapNodes.isMap("android.view.View", null, " map "));
        assertTrue(MapNodes.isMap(null, null, "Map."));
        assertFalse(MapNodes.isMap("android.view.View", null, "Map of your zone with peak pay"));
        assertFalse(MapNodes.isMap("android.view.View", null, "Maps"));
        assertFalse(MapNodes.isMap("android.view.View", null, "Navigate"));
    }

    @Test
    public void signsOfAnOfferInWords() {
        assertTrue(MapNodes.showsOffer(Collections.singletonList("$7.50")));
        assertTrue(MapNodes.showsOffer(Collections.singletonList("+$2.00")));
        assertTrue(MapNodes.showsOffer(Collections.singletonList("Decline")));
        assertTrue(MapNodes.showsOffer(Collections.singletonList("Accept")));
        assertTrue(MapNodes.showsOffer(Collections.singletonList("2 stops (7.2 mi) • 21 min")));
        assertTrue(MapNodes.showsOffer(Collections.singletonList("New Delivery!")));
        assertTrue(MapNodes.showsOffer(Collections.singletonList("Are you sure you want to decline this offer?")));
        assertFalse(MapNodes.showsOffer(Collections.singletonList("Google Map")));
        assertFalse(MapNodes.showsOffer(Arrays.asList("Finding offers", "Navigate")));
        assertFalse(MapNodes.showsOffer(Collections.<String>emptyList()));
        assertFalse(MapNodes.showsOffer(null));
    }

    @Test
    public void wordsAreANodesTextAndDescription() {
        assertTrue(MapNodes.words(Collections.<CharSequence>singletonList("  $7.50 "), "Map").contains("$7.50"));
        assertTrue(MapNodes.words(null, "Google Map").contains("Google Map"));
        assertTrue(MapNodes.words(Collections.<CharSequence>singletonList(null), null).isEmpty());
    }
}
