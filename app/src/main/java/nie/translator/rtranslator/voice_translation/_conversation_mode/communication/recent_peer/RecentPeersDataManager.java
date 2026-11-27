/*
 * Copyright 2016 Luca Martino.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copyFile of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nie.translator.rtranslator.voice_translation._conversation_mode.communication.recent_peer;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Room;
import java.util.ArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import nie.translator.rtranslator.databases.general.AppDatabase;
import nie.translator.rtranslator.databases.general.dao.MyDao;
import nie.translator.rtranslator.databases.general.entities.RecentPeerEntity;
import nie.translator.rtranslator.tools.Tools;

public class RecentPeersDataManager {
    private AppDatabase database;
    private ArrayList<RecentPeer> cachedRecentPeers;

    public RecentPeersDataManager(Context context) {
        database = Room.databaseBuilder(context, AppDatabase.class, "consumption_credit_dp").build();
    }

    public void insertRecentPeer(@NonNull final String deviceId, final String uniqueName, final Bitmap userImage) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                MyDao dao = database.myDao();
                RecentPeerEntity recentPeerEntity = new RecentPeerEntity();
                recentPeerEntity.deviceId = deviceId;
                recentPeerEntity.uniqueName = uniqueName;
                if (userImage != null) {
                    recentPeerEntity.userImage = Tools.convertBitmapToBytes(userImage);
                }
                dao.insertRecentPeers(recentPeerEntity);
            }
        }).start();
        // updating the cache
        if (cachedRecentPeers != null) {
            RecentPeer recentPeer2 =
                    new RecentPeer(deviceId, uniqueName, userImage);

            boolean found = false;
            for (int i = 0; i < cachedRecentPeers.size() && !found; i++) {
                if (cachedRecentPeers.get(i).getDeviceID().equals(deviceId)) {
                    found = true;
                    cachedRecentPeers.set(i, recentPeer2);
                }
            }
            if (!found) {
                cachedRecentPeers.add(recentPeer2);
            }
        }
    }

    public void deleteRecentPeer(final RecentPeer peer) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                MyDao dao = database.myDao();
                RecentPeerEntity recentPeerEntity = new RecentPeerEntity();
                recentPeerEntity.deviceId = peer.getDeviceID();
                recentPeerEntity.uniqueName = peer.getUniqueName();
                recentPeerEntity.userImage = peer.getUserImageData();
                dao.deleteRecentPeers(recentPeerEntity);
            }
        }, "deleteRecentPeer").start();
        // updating the cache
        if (cachedRecentPeers != null) {
            boolean found = false;
            for (int i = 0; i < cachedRecentPeers.size() && !found; i++) {
                if (cachedRecentPeers.get(i).getDeviceID().equals(peer.getDeviceID())) {
                    found = true;
                    cachedRecentPeers.remove(i);
                }
            }
        }
    }


    public void getRecentPeers(final RecentPeersListener responseListener) {
        if (cachedRecentPeers == null) {
            obtainRecentPeers(database.myDao(), responseListener);
        } else {
            responseListener.onRecentPeersObtained(cachedRecentPeers);
        }
    }

    // one background thread, results on the main thread: what AsyncTask.execute did (AsyncTask is deprecated)
    private static final Executor DB_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static void obtainRecentPeers(@Nullable final MyDao myDao, final RecentPeersListener responseListener) {
        DB_EXECUTOR.execute(() -> {
            if (myDao == null) return;
            ArrayList<RecentPeer> recentPeers = new ArrayList<>();
            for (RecentPeerEntity aRecentPeersArrayEntity : myDao.loadRecentPeers()) {
                recentPeers.add(aRecentPeersArrayEntity.getRecentPeer());
            }
            MAIN.post(() -> responseListener.onRecentPeersObtained(recentPeers));
        });
    }

    public interface RecentPeersListener {
        void onRecentPeersObtained(ArrayList<RecentPeer> recentPeers);
    }


    public void getRecentPeer(final String deviceID, final RecentPeerListener responseListener) {
        if (cachedRecentPeers == null) {
            obtainRecentPeer(database.myDao(), deviceID, responseListener);
        } else {
            int index = cachedRecentPeers.indexOf(new RecentPeer(deviceID));
            if (index != -1) {
                responseListener.onRecentPeerObtained(cachedRecentPeers.get(index));
            } else {
                responseListener.onRecentPeerObtained(null);
            }
        }
    }

    private static void obtainRecentPeer(@Nullable final MyDao myDao, @Nullable String key, final RecentPeerListener responseListener) {
        final String k = key == null ? "" : key;
        DB_EXECUTOR.execute(() -> {
            RecentPeerEntity recentPeerEntity = myDao != null ? myDao.loadRecentPeer(k) : null;
            RecentPeer recentPeer = recentPeerEntity != null ? recentPeerEntity.getRecentPeer() : null;
            MAIN.post(() -> responseListener.onRecentPeerObtained(recentPeer));
        });
    }

    public void getRecentPeerByName(final String uniqueName, final RecentPeerListener responseListener) {
        if (cachedRecentPeers == null) {
            obtainRecentPeerByName(database.myDao(), uniqueName, responseListener);
        } else {
            int index=-1;
            for (int i = 0; i < cachedRecentPeers.size(); i++) {
                RecentPeer recentPeer = cachedRecentPeers.get(i);
                String uniqueName1 = recentPeer.getUniqueName();
                if (uniqueName1.length() > 0 && uniqueName1.equals(uniqueName)) {
                    index=i;
                }
            }

            if (index != -1) {
                responseListener.onRecentPeerObtained(cachedRecentPeers.get(index));
            } else {
                responseListener.onRecentPeerObtained(null);
            }
        }
    }

    private static void obtainRecentPeerByName(@Nullable final MyDao myDao, @Nullable String key, final RecentPeerListener responseListener) {
        final String k = key == null ? "" : key;
        DB_EXECUTOR.execute(() -> {
            RecentPeerEntity recentPeerEntity = myDao != null ? myDao.loadRecentPeerByName(k) : null;
            RecentPeer recentPeer = recentPeerEntity != null ? recentPeerEntity.getRecentPeer() : null;
            MAIN.post(() -> responseListener.onRecentPeerObtained(recentPeer));
        });
    }

    public interface RecentPeerListener {
        void onRecentPeerObtained(@Nullable RecentPeer recentPeer);
    }
}
