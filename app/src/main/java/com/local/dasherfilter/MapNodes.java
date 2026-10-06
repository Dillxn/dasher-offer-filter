package com.local.dasherfilter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Map views inside Dasher's screens (the waiting map, the delivery and navigation maps): a Google Maps or Mapbox
 * view redraws many times a second and can hold thousands of nodes, none of them an offer's, and every node read is a
 * call Dasher's UI thread must serve while it draws the map. A read counts such a node and leaves its children unread,
 * unless the node's own words show a sign of an offer. Also what words Android already delivered (an event's text)
 * say of an offer, without asking Dasher anything.
 */
final class MapNodes {
    /** "map" as a word of a view's ID: map, mapView, map_view, googleMap, nav_map; not bitmap or heatmap. */
    private static final Pattern MAP_ID = Pattern.compile("(?:^|[^A-Za-z])[Mm]ap|[a-z]Map");
    /** A dollar amount anywhere in a label ("$7.50", "+$2"). */
    private static final Pattern MONEY = Pattern.compile("\\$\\s*\\d");

    /**
     * Whether a node is a map view whose children need not be read: its class name ends in "MapView"
     * (com.google.android.gms.maps.MapView, com.mapbox.maps.MapView), it is a SurfaceView or TextureView whose view ID
     * names a map, or its content description is "Google Map" or "Map".
     */
    static boolean isMap(CharSequence className, CharSequence viewId, CharSequence description) {
        String name = className == null ? "" : className.toString();
        if (name.endsWith("MapView")) return true;
        if ((name.endsWith("SurfaceView") || name.endsWith("TextureView")) && viewId != null) {
            String id = viewId.toString();
            int entry = id.indexOf(":id/");
            if (MAP_ID.matcher(entry < 0 ? id : id.substring(entry + 4)).find()) return true;
        }
        if (description == null) return false;
        String said = OfferEvidence.normalize(description.toString()).toLowerCase(Locale.US).replaceAll("[.!…]+$", "");
        return said.equals("google map") || said.equals("map");
    }

    /**
     * Whether words show any sign of an offer: pay or any dollar amount, a distance, time or stops, Accept or Decline,
     * Dasher's question about declining, or a new offer's headline.
     */
    static boolean showsOffer(List<String> labels) {
        if (labels == null || labels.isEmpty() || !OfferEvidence.bounded(labels)) return false;
        for (String label : labels) {
            if (MONEY.matcher(label).find()) return true;
            if (OfferControls.isButton(label, "accept") || OfferControls.isButton(label, "decline")) return true;
        }
        if (DasherScene.showsNewOffer(labels) || DeclineConfirmation.isSurface(labels)) return true;
        OfferSnapshot facts = OfferParser.parse(labels);
        return facts.payCents != null || facts.payAtMostCents != null || facts.miles != null || facts.minutes != null
                || facts.stops != null;
    }

    /** A node's or an event's own words (its text and its description), normalized; empty for none. */
    static List<String> words(List<CharSequence> text, CharSequence description) {
        List<String> words = new ArrayList<>(2);
        if (text != null) for (CharSequence part : text) add(words, part);
        add(words, description);
        return words;
    }

    private static void add(List<String> words, CharSequence value) {
        if (value == null || value.length() == 0) return;
        String word = OfferEvidence.normalize(value.toString());
        if (!word.isEmpty() && words.size() < OfferEvidence.MAX_LABELS) words.add(word);
    }

    private MapNodes() {}
}
