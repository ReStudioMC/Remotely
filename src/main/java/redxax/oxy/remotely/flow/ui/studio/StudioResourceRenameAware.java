package redxax.oxy.remotely.flow.ui.studio;

public interface StudioResourceRenameAware {
    default boolean deferResourceRename(String type, String oldId, String newId, Runnable mutation) {
        return false;
    }

    default void resourceRenamed(String type, String oldId, String newId) {
    }
}
