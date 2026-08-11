package space.nows.mcnows.installer;

import java.util.logging.Logger;

public final class NowsInstallerOffline {
    private static final Logger LOG = NowsInstaller.logger(NowsInstallerOffline.class);

    private NowsInstallerOffline() {}

    public static void main(String[] args) throws Exception {
        NowsInstaller.Options options = NowsInstaller.Options.parse(args).withEmbeddedOfflinePayload();
        NowsInstaller.install(options, new NowsInstaller.InstallerListener() {
            @Override
            public void log(String message) {
                LOG.info(message);
            }
        });
    }
}
