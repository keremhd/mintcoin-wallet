/*
 * Copyright 2011-2014 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package cc.mintcoin.wallet.service;

import java.io.File;
import java.io.IOException;

import android.content.*;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.common.io.Files;

import cc.mintcoin.wallet.Configuration;
import cc.mintcoin.wallet.Constants;
import cc.mintcoin.wallet.WalletApplication;
import android.app.AlarmManager;
import android.app.DownloadManager;
import android.app.PendingIntent;
import android.app.DownloadManager.Query;
import android.preference.PreferenceManager;
import android.text.GetChars;
import android.text.format.DateUtils;

/**
 * @author Kerem Hadimli
 */
public class DownloadCompleteReceiver extends BroadcastReceiver
{
	private static final Logger log = LoggerFactory.getLogger(DownloadCompleteReceiver.class);
    private Context mCtx = null;
    
    public static final String INTENT_EXTRA_SKIP_OBB_INIT = "SKIP_OBB_INIT";

	@Override
	public void onReceive(final Context context, final Intent intent) {
		log.info("got broadcast intent: " + intent);

		if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
			updateDownloadState(context);
		}
	}

	public static final String OBB_MAIN_FILE = "cc.mintcoin.wallet.OBB_MAIN_FILE";
	public static final String OBB_PATCH_FILE = "cc.mintcoin.wallet.OBB_PATCH_FILE";
	
	public static final String OBB_MAIN_FILENAME = "main.obb";
	public static final String OBB_PATCH_FILENAME = "patch.obb";
	
	public static final long REQUEST_ID_NONE = -1;
	
	public static File getObbPath(WalletApplication app, String obbFilename) {
		return new File(app.getExternalFilesDir(null), obbFilename);
		//return new File(app.getDir("blockchain-dl", Context.MODE_PRIVATE), obbFilename);
	}
	
	private static int STATE_NONE_NONE = 0;
	private static int STATE_MAIN_NONE = 1;
	private static int STATE_MAIN_DOWNLOADING = 2;
	private static int STATE_PATCH_NONE = 3;
	private static int STATE_PATCH_DOWNLOADING = 4;
	
	private static int getDownloadState(Configuration config) {
		long requestId = config.getObbActiveDownloadId();
		String requestType = config.getObbActiveDownloadType();
		int state = -1;
		
		if (requestId == REQUEST_ID_NONE && requestType == null)
			state = STATE_NONE_NONE;
		
		if (requestId == REQUEST_ID_NONE && OBB_MAIN_FILE.equals(requestType))
			state = STATE_MAIN_NONE;
		
		if (requestId != REQUEST_ID_NONE && OBB_MAIN_FILE.equals(requestType))
			state = STATE_MAIN_DOWNLOADING;
		
		if (requestId == REQUEST_ID_NONE && OBB_PATCH_FILE.equals(requestType))
			state = STATE_PATCH_NONE;
		
		if (requestId != REQUEST_ID_NONE && OBB_PATCH_FILE.equals(requestType))
			state = STATE_PATCH_DOWNLOADING;
		
		if (state < 0) {
			log.info("Invalid download state, requestId=" + requestId + " requestType=" + requestType);
			state = STATE_NONE_NONE;
		}
		
		log.info("getDownloadState() = " + state);
		return state;
	}
	
	public static void startDownload(final Context context) {
		WalletApplication app = (WalletApplication)context.getApplicationContext();
		Configuration config = app.getConfiguration();
		
		int state = getDownloadState(config);
		log.info("startDownload(): state=" + state);
		
		if (state == STATE_NONE_NONE) {
			log.info("startDownload(): starting");
			
			getObbPath(app, OBB_MAIN_FILENAME).delete();
			getObbPath(app, OBB_PATCH_FILENAME).delete();
			
			config.setObbActiveDownloadType(OBB_MAIN_FILE);
			
			state = getDownloadState(config);
			log.info("startDownload(): new state=" + state);
			
			updateDownloadState(context);
		}
	}
	
	public static boolean isDownloading(final Context context) {
		WalletApplication app = (WalletApplication)context.getApplicationContext();
		Configuration config = app.getConfiguration();

		return (getDownloadState(config) != STATE_NONE_NONE);
	}
	
	public static class DownloadDetail
	{
		public int mainBytesDownloaded;
		public int mainBytesTotal;
		public int patchBytesDownloaded;
		public int patchBytesTotal;
		public String status;
		public String statusDetail;
	};
	
	public static DownloadDetail getDownloadDetail(final Context context) {
		DownloadDetail detail = new DownloadDetail();
		return detail;
		//int state = getDownloadState(context);
		/*if (state == STATE_NONE_NONE) {
			detail.mainBytesDownloaded = 0;
			detail.mainBytesTotal = 1;
			detail.patchBytesDownloaded = 0;
			detail.patchBytesTotal = 1;
			detail.status = "STATE_NONE_NONE";
			detail.statusDetail = "";
		}*/
		
	}
	
	private static boolean isObbValid(final WalletApplication app, String obbFile) {
		File file = null;
		String hash = null;
		if (OBB_MAIN_FILE.equals(obbFile)) {
			file = getObbPath(app, OBB_MAIN_FILENAME);
			hash = app.getConfiguration().getObbMainHash();
		}
		else if (OBB_PATCH_FILE.equals(obbFile)) {
			file = getObbPath(app, OBB_PATCH_FILENAME);
			hash = app.getConfiguration().getObbPatchHash();
		}
		else
			return false;
		
		try {
			HashCode fileHash = Files.hash(file, Hashing.sha256());
			
			log.info("isObbValid(): obbfile=" + obbFile + " file=" + file + " hash=" + hash + " calculatedHash=" + fileHash.toString());
			
			if (fileHash.toString().toLowerCase().equals(hash.toLowerCase()))
				return true;
		}
		catch (IOException e) {
		}
		
		file.delete();
		return false;
	}
	
	public static boolean isObbAvailable(final Context context) {
		WalletApplication app = (WalletApplication)context.getApplicationContext();
		
		boolean mainValid = isObbValid(app, OBB_MAIN_FILE);
		boolean patchValid = isObbValid(app, OBB_PATCH_FILE);
		
		return (mainValid && patchValid);
	}
	
	public static void updateDownloadState(final Context context) {
		WalletApplication app = (WalletApplication)context.getApplicationContext();
		Configuration config = app.getConfiguration();
		DownloadManager manager = (DownloadManager)app.getSystemService(Context.DOWNLOAD_SERVICE);

		int state = getDownloadState(config);
		if (state == STATE_MAIN_NONE) {
			
			if (isObbValid(app, OBB_MAIN_FILE)) {
				// Skip download
				config.setObbActiveDownloadType(OBB_PATCH_FILE); // -> STATE_PATCH_NONE
			}
			else {
				DownloadManager.Request request = new DownloadManager.Request(Uri.parse(config.getObbMainUrl()));
				request.setDescription("Initial blockchain file");
				request.setTitle("Mintcoin Initial Blockchain (Base)");
				request.setVisibleInDownloadsUi(true);
				request.setDestinationUri(Uri.fromFile(getObbPath(app, OBB_MAIN_FILENAME + ".t")));
				long requestId = manager.enqueue(request);
				config.setObbActiveDownloadId(requestId); // -> STATE_MAIN_DOWNLOADING
			}
		}
		else if (state == STATE_MAIN_DOWNLOADING) {
			Query query = new Query();
			query.setFilterById(config.getObbActiveDownloadId());
			Cursor c = manager.query(query);
			if (c.moveToFirst()) {
				int status = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS));
				if (status == DownloadManager.STATUS_SUCCESSFUL) {
					log.info("Main download successful");
					
					Uri localUri = Uri.parse(c.getString(c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)));
					File localFile = new File(localUri.getPath());
					localFile.renameTo(getObbPath(app, OBB_MAIN_FILENAME));
					
					// -> STATE_PATCH_NONE
					config.setObbActiveDownloadId(REQUEST_ID_NONE);
					config.setObbActiveDownloadType(OBB_PATCH_FILE);
				}
				else if (status == DownloadManager.STATUS_FAILED || status == DownloadManager.STATUS_PAUSED) {
					log.info("Main download failed");
					
					manager.remove(config.getObbActiveDownloadId());
					
					// -> STATE_NONE_NONE
					config.setObbActiveDownloadId(REQUEST_ID_NONE);
					config.setObbActiveDownloadType(null);
				}
				else {
					log.info("Main download not completed yet, status=" + status);
					// -> Keep current state
				}
			}
			else {
				log.info("Main download cannot be found in DownloadManager");
				
				// -> STATE_NONE_NONE
				config.setObbActiveDownloadId(REQUEST_ID_NONE);
				config.setObbActiveDownloadType(null);
			}
		}
		else if (state == STATE_PATCH_NONE) {
			if (isObbValid(app, OBB_PATCH_FILE)) {
				// Skip download
				config.setObbActiveDownloadType(null); // -> STATE_NONE_NONE
			}
			else {
				DownloadManager.Request request = new DownloadManager.Request(Uri.parse(config.getObbPatchUrl()));
				request.setDescription("Latest patch for initial blockchain");
				request.setTitle("Mintcoin Initial Blockchain (Patch)");
				request.setVisibleInDownloadsUi(true);
				request.setDestinationUri(Uri.fromFile(getObbPath(app, OBB_PATCH_FILENAME + ".t")));
				long requestId = manager.enqueue(request);
				
				config.setObbActiveDownloadId(requestId); // -> STATE_PATCH_DOWNLOADING
			}
		}
		else if (state == STATE_PATCH_DOWNLOADING) {
			Query query = new Query();
			query.setFilterById(config.getObbActiveDownloadId());
			Cursor c = manager.query(query);
			if (c.moveToFirst()) {
				int status = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS));
				if (status == DownloadManager.STATUS_SUCCESSFUL) {
					log.info("Patch download successful");
					
					Uri localUri = Uri.parse(c.getString(c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)));
					File localFile = new File(localUri.getPath());
					localFile.renameTo(getObbPath(app, OBB_PATCH_FILENAME));
					
					// -> STATE_NONE_NONE
					config.setObbActiveDownloadId(REQUEST_ID_NONE);
					config.setObbActiveDownloadType(null);
				}
				else if (status == DownloadManager.STATUS_FAILED || status == DownloadManager.STATUS_PAUSED) {
					log.info("Patch download failed");
					
					manager.remove(config.getObbActiveDownloadId());
					getObbPath(app, OBB_MAIN_FILENAME).delete();
					
					// -> STATE_NONE_NONE
					config.setObbActiveDownloadId(REQUEST_ID_NONE);
					config.setObbActiveDownloadType(null);
				}
				else {
					log.info("Patch download not completed yet, status=" + status);
					// -> Keep current state
				}
			}
			else {
				log.info("Patch download cannot be found in DownloadManager");
				
				// -> STATE_NONE_NONE
				config.setObbActiveDownloadId(REQUEST_ID_NONE);
				config.setObbActiveDownloadType(null);
			}
		}
		else {
			return;
		}
		
		int newState = getDownloadState(config);
		if (newState != state) {
			log.info("State changed " + state + "->" + newState);
			
			if (newState == STATE_NONE_NONE) {
				// Downloads are finished or failed; we can start BlockChainService
				//
				final Intent serviceIntent = new Intent(context, BlockchainServiceImpl.class);
				if (!isObbAvailable(context)) {
					serviceIntent.putExtra(INTENT_EXTRA_SKIP_OBB_INIT, true);
				}
				context.startService(serviceIntent);
			}
			else {
				log.info("Recursing for new state");
				updateDownloadState(context);
			}
		}
	}

}
