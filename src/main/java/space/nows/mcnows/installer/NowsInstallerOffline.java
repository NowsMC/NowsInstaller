package space.nows.mcnows.installer;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public final class NowsInstallerOffline {
    private static final Logger LOG = NowsInstaller.logger(NowsInstallerOffline.class);

    private NowsInstallerOffline() {}

    public static void main(String[] args) throws Exception {
        if (!hasCliFlag(args)) {
            NowsInstallerGui.mainOffline(stripLocalOfflineOptions(args));
            return;
        }
        NowsInstaller.Options options = NowsInstaller.Options.parse(stripLocalOfflineOptions(args, "--cli"))
                .withEmbeddedOfflinePayload();
        NowsInstaller.install(options, new NowsInstaller.InstallerListener() {
            @Override
            public void log(String message) {
                LOG.info(message);
            }
        });
    }

    private static String[] stripLocalOfflineOptions(String[] args) {
        return stripLocalOfflineOptions(args, null);
    }

    private static String[] stripLocalOfflineOptions(String[] args, String extraFlag) {
        List<String> cleaned = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--offline".equals(arg) || arg.equals(extraFlag)) {
                continue;
            }
            if ("--manifest".equals(arg) || "--artifactDir".equals(arg)) {
                i++;
                continue;
            }
            cleaned.add(arg);
        }
        return cleaned.toArray(new String[0]);
    }

    private static boolean hasCliFlag(String[] args) {
        for (String arg : args) {
            if ("--cli".equals(arg)) {
                return true;
            }
        }
        return false;
    }
}
