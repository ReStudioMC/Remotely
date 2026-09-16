package redxax.oxy.remotely.flow.ui;

public interface StudioCloseHandledScreen {
    void setStudioCloseHandler(Runnable closeHandler);

    boolean isStudioCloseAnimationFinished();
}
