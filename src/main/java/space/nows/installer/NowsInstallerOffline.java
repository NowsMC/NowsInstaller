/*
 * Copyright 2026 TamKungZ_ (Nows MC — https://nows.space)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package space.nows.installer;

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
