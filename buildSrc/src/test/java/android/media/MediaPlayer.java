package android.media;

/** Stand-in for the egress tests. */
public class MediaPlayer {
    public void setDataSource(String path) {}
    public void setVolume(float left, float right) {}
    /** The real one takes (Context, Uri); the stand-in keeps the name and static shape. */
    public static MediaPlayer create(Object context, String uri) { return new MediaPlayer(); }
}
