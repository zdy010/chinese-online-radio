package android.media.broadcastradio;

import android.os.Handler;
import java.util.List;

/**
 * 编译期 stub：broadcastradio 是 @SystemApi，不在公共 SDK 的 android.jar 里，
 * 这里只声明硬件收音机探测用到的最小签名。
 *
 * 运行时 framework 的同名真类按 parent-first 委托遮蔽本类，实际执行的是系统实现；
 * 设备没有这套框架时才会落到本 stub 的方法体，抛出的异常被 HardwareRadioProbe 兜底成“没测成”。
 */
public class RadioManager {
    public static class BandConfig {
    }

    public static class BandDescriptor {
        public BandConfig createBandConfig() {
            throw new UnsupportedOperationException("stub");
        }
    }

    public static class RadioModule {
        public int getId() {
            throw new UnsupportedOperationException("stub");
        }

        public BandDescriptor[] getBands() {
            throw new UnsupportedOperationException("stub");
        }
    }

    public List<RadioModule> getModules() {
        throw new UnsupportedOperationException("stub");
    }

    public RadioTuner openTuner(int moduleId, BandConfig config, boolean withAudio,
            RadioTuner.Callback callback, Handler handler) {
        throw new UnsupportedOperationException("stub");
    }
}
