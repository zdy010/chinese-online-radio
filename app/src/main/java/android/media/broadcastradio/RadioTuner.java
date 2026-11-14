package android.media.broadcastradio;

/**
 * 编译期 stub：broadcastradio 是 @SystemApi，不在公共 SDK 的 android.jar 里，说明见 RadioManager.java。
 */
public class RadioTuner {
    public static abstract class Callback {
        public abstract void onConfigurationChanged(RadioManager.BandConfig config);

        public abstract void onError(int status);
    }

    public void close() {
        throw new UnsupportedOperationException("stub");
    }
}
