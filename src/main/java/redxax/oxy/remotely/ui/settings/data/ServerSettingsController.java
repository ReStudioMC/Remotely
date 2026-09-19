package redxax.oxy.remotely.ui.settings.data;

import redxax.oxy.remotely.settings.server.ServerSettingsSnapshot;
import redxax.oxy.remotely.metadata.catalog.ServerSettingsCatalogService;
import restudio.rebase.api.RebaseAPI;
import restudio.rebase.instance.Instance;

public class ServerSettingsController extends DesktopServerSettingsDataController {
    public ServerSettingsController(Instance instance, ServerSettingsSnapshot snapshot) {
        super(instance, snapshot);
    }

    public ServerSettingsController(Instance instance, ServerSettingsSnapshot snapshot, RebaseAPI api) {
        super(instance, snapshot, api);
    }

    public ServerSettingsController(Instance instance, ServerSettingsSnapshot snapshot, RebaseAPI api,
                                    ServerSettingsCatalogService.View catalogs) {
        super(instance, snapshot, api, catalogs);
    }
}
