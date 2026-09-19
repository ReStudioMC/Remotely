package redxax.oxy.remotely.util;

public final class DesktopTaskIdentities {
    private DesktopTaskIdentities() {
    }

    public static void install() {
        TaskIdentities.install(new TaskIdentities.Access() {
            @Override
            public Object current() {
                return Thread.currentThread();
            }

            @Override
            public void interrupt() {
                Thread.currentThread().interrupt();
            }

            @Override
            public String name() {
                return Thread.currentThread().getName();
            }

            @Override
            public void setName(String name) {
                if (name != null) {
                    Thread.currentThread().setName(name);
                }
            }
        });
    }
}
