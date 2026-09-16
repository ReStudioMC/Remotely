package redxax.oxy.remotely.web;

import org.teavm.jso.JSBody;
import redxax.oxy.remotely.web.platform.BrowserLaunchSession;

public final class RemotelyBrowserMain {
    private static RemotelyBrowserComposition.Runtime runtime;
    private static boolean startupInFlight;
    private static boolean loopStarted;

    private RemotelyBrowserMain() {
    }

    public static void main(String[] args) {
        if (runtime != null || startupInFlight) {
            return;
        }
        startupInFlight = true;
        BrowserLaunchSession.launch(new BrowserLaunchSession.Callback() {
            @Override
            public void ready(BrowserLaunchSession.Metadata metadata) {
                if (runtime != null) {
                    return;
                }
                try {
                    runtime = RemotelyBrowserComposition.start("remotely-canvas", metadata);
                    startupInFlight = false;
                    if (!loopStarted) {
                        loopStarted = true;
                        startLoop();
                    }
                } catch (Throwable error) {
                    startupInFlight = false;
                    fail(error.getMessage());
                }
            }

            @Override
            public void failed(String message) {
                startupInFlight = false;
                fail(message);
            }
        });
    }

    public static void renderFrame(double timestamp) {
        if (runtime != null) {
            runtime.screenClient().renderFrame(timestamp);
        }
    }

    public static void fail(String message) {
        setStatus(message == null ? "Remotely web launch failed" : message);
    }

    @JSBody(params = {"message"}, script = "const value = String(message || 'Remotely Web Could Not Start').slice(0, 240); if (typeof window.__remotelySetStatus === 'function') { window.__remotelySetStatus(value); } else { document.title = 'Remotely'; }")
    private static native void setStatus(String message);

    @JSBody(script = """
            function loop(timestamp) {
                javaMethods.get('redxax.oxy.remotely.web.RemotelyBrowserMain.renderFrame(D)V').invoke(timestamp);
                window.requestAnimationFrame(loop);
            }
            window.requestAnimationFrame(loop);
            """)
    private static native void startLoop();
}
