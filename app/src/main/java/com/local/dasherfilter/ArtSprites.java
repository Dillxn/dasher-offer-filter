package com.local.dasherfilter;

/**
 * The app's pictures from the website's 3D models, written by tools/art/render-sprites.cjs with the pictures
 * (res/drawable-nodpi/art_*.webp); do not edit. Each one's size in its own pixels (made for 640 dpi), the point it is
 * placed by, how many of its pixels make one unit of its model there, and its other points, in order, x then y.
 */
final class ArtSprites {
    private ArtSprites() {}

    /** Its points: shoulder. */
    static final Art.Sprite MASCOT_ON = new Art.Sprite(R.drawable.art_mascot_on, 214, 166, 106.75f, 68.65f, 96.34f, 168.26f, 71.21f);
    /** Its points: shoulder. */
    static final Art.Sprite MASCOT_PAUSED = new Art.Sprite(R.drawable.art_mascot_paused, 214, 166, 106.75f, 68.65f, 96.34f, 168.26f, 71.21f);
    /** Its points: shoulder. */
    static final Art.Sprite MASCOT_OFF = new Art.Sprite(R.drawable.art_mascot_off, 214, 166, 106.75f, 68.65f, 96.34f, 168.26f, 71.21f);
    /** Its points: shoulder. */
    static final Art.Sprite MASCOT_ON_NIGHT = new Art.Sprite(R.drawable.art_mascot_on_night, 214, 166, 106.75f, 68.65f, 96.34f, 168.26f, 71.21f);
    /** Its points: shoulder. */
    static final Art.Sprite MASCOT_PAUSED_NIGHT = new Art.Sprite(R.drawable.art_mascot_paused_night, 214, 166, 106.75f, 68.65f, 96.34f, 168.26f, 71.21f);
    /** Its points: shoulder. */
    static final Art.Sprite MASCOT_OFF_NIGHT = new Art.Sprite(R.drawable.art_mascot_off_night, 214, 166, 106.75f, 68.65f, 96.34f, 168.26f, 71.21f);
    /** Its points: origin. */
    static final Art.Sprite ARM_ON = new Art.Sprite(R.drawable.art_arm_on, 36, 46, 1.26f, 2.21f, 96.34f, -60.25f, -0.35f);
    /** Its points: origin. */
    static final Art.Sprite ARM_PAUSED = new Art.Sprite(R.drawable.art_arm_paused, 36, 46, 1.26f, 2.21f, 96.34f, -60.25f, -0.35f);
    /** Its points: origin. */
    static final Art.Sprite ARM_OFF = new Art.Sprite(R.drawable.art_arm_off, 36, 46, 1.26f, 2.21f, 96.34f, -60.25f, -0.35f);
    /** Its points: origin. */
    static final Art.Sprite ARM_OFF_NIGHT = new Art.Sprite(R.drawable.art_arm_off_night, 36, 46, 1.26f, 2.21f, 96.34f, -60.25f, -0.35f);
    /** */
    static final Art.Sprite SUN = new Art.Sprite(R.drawable.art_sun, 148, 148, 73.75f, 73.75f, 71.96f);
    /** */
    static final Art.Sprite MOON = new Art.Sprite(R.drawable.art_moon, 97, 100, 60.00f, 43.00f, 60.00f);
    /** */
    static final Art.Sprite CLOUD = new Art.Sprite(R.drawable.art_cloud, 227, 127, 110.00f, 61.00f, 60.00f);
    /** */
    static final Art.Sprite CLOUD_NIGHT = new Art.Sprite(R.drawable.art_cloud_night, 227, 127, 110.00f, 61.00f, 60.00f);
    /** */
    static final Art.Sprite CAR = new Art.Sprite(R.drawable.art_car, 170, 129, 85.00f, 124.57f, 88.98f);
    /** */
    static final Art.Sprite CAR_NIGHT = new Art.Sprite(R.drawable.art_car_night, 170, 129, 85.00f, 124.57f, 88.98f);
    /** */
    static final Art.Sprite HOUSE = new Art.Sprite(R.drawable.art_house, 105, 118, 52.50f, 117.30f, 90.16f);
    /** */
    static final Art.Sprite HOUSE_NIGHT = new Art.Sprite(R.drawable.art_house_night, 105, 118, 52.50f, 117.30f, 90.16f);
    /** */
    static final Art.Sprite TREE = new Art.Sprite(R.drawable.art_tree, 38, 102, 19.00f, 99.53f, 49.51f);
    /** */
    static final Art.Sprite TREE_NIGHT = new Art.Sprite(R.drawable.art_tree_night, 38, 102, 19.00f, 99.53f, 49.51f);
    /** Its points: left, right, capEnd, slice. */
    static final Art.Sprite BLOCK_KEEP = new Art.Sprite(R.drawable.art_block_keep, 87, 129, 43.25f, 6.24f, 84.78f, 0.92f, 73.67f, 85.58f, 73.67f, 43.25f, 27.33f, 43.25f, 73.67f);
    /** Its points: left, right, capEnd, slice. */
    static final Art.Sprite BLOCK_REVIEW = new Art.Sprite(R.drawable.art_block_review, 87, 129, 43.25f, 6.24f, 84.78f, 0.92f, 73.67f, 85.58f, 73.67f, 43.25f, 27.33f, 43.25f, 73.67f);
    /** Its points: left, right, capEnd, slice. */
    static final Art.Sprite BLOCK_DECLINE = new Art.Sprite(R.drawable.art_block_decline, 87, 129, 43.25f, 6.24f, 84.78f, 0.92f, 73.67f, 85.58f, 73.67f, 43.25f, 27.33f, 43.25f, 73.67f);
    /** Its points: left, right, capEnd, slice. */
    static final Art.Sprite BLOCK_KEEP_NIGHT = new Art.Sprite(R.drawable.art_block_keep_night, 87, 129, 43.25f, 6.24f, 84.78f, 0.92f, 73.67f, 85.58f, 73.67f, 43.25f, 27.33f, 43.25f, 73.67f);
    /** Its points: left, right, capEnd, slice. */
    static final Art.Sprite BLOCK_REVIEW_NIGHT = new Art.Sprite(R.drawable.art_block_review_night, 87, 129, 43.25f, 6.24f, 84.78f, 0.92f, 73.67f, 85.58f, 73.67f, 43.25f, 27.33f, 43.25f, 73.67f);
    /** Its points: left, right, capEnd, slice. */
    static final Art.Sprite BLOCK_DECLINE_NIGHT = new Art.Sprite(R.drawable.art_block_decline_night, 87, 129, 43.25f, 6.24f, 84.78f, 0.92f, 73.67f, 85.58f, 73.67f, 43.25f, 27.33f, 43.25f, 73.67f);
    /** Its points: base, left, right. */
    static final Art.Sprite PINE = new Art.Sprite(R.drawable.art_pine, 51, 149, 25.25f, 1.01f, 0.00f, 25.25f, 144.34f, 1.30f, 144.34f, 49.20f, 144.34f);
    /** Its points: base, left, right. */
    static final Art.Sprite PINE_NIGHT = new Art.Sprite(R.drawable.art_pine_night, 51, 149, 25.25f, 1.01f, 0.00f, 25.25f, 144.34f, 1.30f, 144.34f, 49.20f, 144.34f);
}
