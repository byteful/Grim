package ac.grim.grimac;

import ac.grim.grimac.manager.DiscordManager;
import ac.grim.grimac.manager.InitManager;
import ac.grim.grimac.manager.TickManager;
import ac.grim.grimac.utils.anticheat.PlayerDataManager;
import lombok.Getter;

@Getter
public enum GrimAPI {
    INSTANCE;

    private final PlayerDataManager playerDataManager = new PlayerDataManager();
    private final InitManager initManager = new InitManager();
    private final TickManager tickManager = new TickManager();
    private final DiscordManager discordManager = new DiscordManager();

    private GrimAC plugin;

    public void load(final GrimAC plugin) {
        this.plugin = plugin;
        assert plugin != null : "Something went wrong! The plugin was null. (Load)";

        initManager.load();
    }

    public void start(final GrimAC plugin) {
        this.plugin = plugin;
        assert plugin != null : "Something went wrong! The plugin was null. (Startup)";

        initManager.start();
    }

    public void stop(final GrimAC plugin) {
        this.plugin = plugin;
        assert plugin != null : "Something went wrong! The plugin was null. (Shutdown)";

        initManager.stop();
    }
}
