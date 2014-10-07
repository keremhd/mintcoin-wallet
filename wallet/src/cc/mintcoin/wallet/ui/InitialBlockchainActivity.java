package cc.mintcoin.wallet.ui;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.SignatureException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.runners.ParentRunner;

import cc.mintcoin.wallet.Configuration;
import cc.mintcoin.wallet.Constants;
import cc.mintcoin.wallet.R;
import cc.mintcoin.wallet.WalletApplication;
import cc.mintcoin.wallet.service.BlockchainServiceImpl;
import cc.mintcoin.wallet.service.DownloadCompleteReceiver;

import com.actionbarsherlock.app.ActionBar;
import com.google.bitcoin.core.Address;
import com.google.bitcoin.core.AddressFormatException;
import com.google.bitcoin.core.ECKey;

import android.app.Activity;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;


public class InitialBlockchainActivity extends Activity {
    private LinearLayout mStartLayout;
    private LinearLayout mProgressLayout;
    
    private ProgressBar mPB;
    

    private TextView mStatusText;
    private TextView mProgressFraction;
    private TextView mProgressPercent;
    private TextView mAverageSpeed;
    private TextView mTimeRemaining;

    private View mDashboard;
    private View mCellMessage;

    private Button mPauseButton;
    private Button mWiFiSettingsButton;

    private boolean mStatePaused;
    private int mState;
    
    private boolean fDisableUI = false;
    

    protected void updateState() {
    	if (fDisableUI) {
    		mStartLayout.setVisibility(LinearLayout.GONE);
    		mProgressLayout.setVisibility(LinearLayout.GONE);
    	}
    	else if (!DownloadCompleteReceiver.isDownloading(this)) {
    		mStartLayout.setVisibility(LinearLayout.VISIBLE);
    		mProgressLayout.setVisibility(LinearLayout.GONE);	
    	}
    	else {
    		mStartLayout.setVisibility(LinearLayout.GONE);
    		mProgressLayout.setVisibility(LinearLayout.VISIBLE);
    	}
    }
    
    protected void onCreate(final Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);

		setContentView(R.layout.initial_blockchain);
		
		mStartLayout = (LinearLayout)findViewById(R.id.startLayout);
		mProgressLayout = (LinearLayout)findViewById(R.id.downloadingLayout);
		
		mPB = (ProgressBar)findViewById(R.id.progressBar);
        mStatusText = (TextView)findViewById(R.id.statusText);
        mProgressFraction = (TextView)findViewById(R.id.progressAsFraction);
        mProgressPercent = (TextView)findViewById(R.id.progressAsPercentage);
        mAverageSpeed = (TextView)findViewById(R.id.progressAverageSpeed);
        mTimeRemaining = (TextView)findViewById(R.id.progressTimeRemaining);
        mDashboard = findViewById(R.id.downloaderDashboard);
        mCellMessage = findViewById(R.id.approveCellular);
        mPauseButton = (Button)findViewById(R.id.pauseButton);
        mWiFiSettingsButton = (Button)findViewById(R.id.wifiSettingsButton);
		
		updateState();
	}
    
    public void onSkipDownload(View view)
    {
    	final Intent serviceIntent = new Intent(this, BlockchainServiceImpl.class);
		serviceIntent.putExtra(DownloadCompleteReceiver.INTENT_EXTRA_SKIP_OBB_INIT, true);
		this.startService(serviceIntent);
		
		final Intent walletIntent = new Intent(this, WalletActivity.class);
		this.startActivity(walletIntent);
    }
    
    public void onStartDownload(View view)
    {
    	AsyncTask<Object, Void, String[]> fetchInformation = new AsyncTask<Object, Void, String[]>() {
    		protected String[] doInBackground(Object... params) {
    			BufferedReader in = null;
    	    	try {
        			URL url = new URL((String)params[0]);
        			long maximumTime = (Long)params[1];

        			Address signAddress = new Address(Constants.NETWORK_PARAMETERS, Constants.DONATION_ADDRESS);
    	    		Pattern pattern = Pattern.compile("^([0-9]+)\\s+(https?://\\S+)\\s+(https?://\\S+)\\s+([0-9a-fA-F]+)\\s+([0-9a-fA-F]+)\\s+(\\S+)$");
        			
    	    		in = new BufferedReader(new InputStreamReader(url.openStream()));
	        	    String str;
	        	    while ((str = in.readLine()) != null) {
	        	        // Format:
	        	    	// "# comment"
	        	    	// or
	        	    	// "time url1 url2 hash1 hash2 signature"
	        	    	// time: uint unix time of the newest block
	        	    	// url1: url of 1st file
	        	    	// url2: url of 2nd file
	        	    	// hash1: hex sha256 hash of 1st file
	        	    	// hash2: hex sha256 hash of 2nd file
	        	    	// signature: "hexhash1hexhash2" signed by Constants.DONATION_ADDRESS, mintcoin-client format
	        	    	
	        	    	// File is sorted in time, decreasing order
	        	    	str = str.trim();
	        	    	if (str.length() == 0 || str.charAt(0) == '#')
	        	    		continue;

	        	    	Matcher m = pattern.matcher(str);
	        	    	if (m.matches()) {
	        	    		long t = Long.parseLong(m.group(1));
	        	    		String url1 = m.group(2);
	        	    		String url2 = m.group(3);
	        	    		String hash1 = m.group(4);
	        	    		String hash2 = m.group(5);
	        	    		String signature = m.group(6);
	        	    		
	        	    		if (t < maximumTime) {
	        	    			// test signature
	        	    			try {
	        	    				String message = hash1 + hash2;
	        	    				ECKey key = ECKey.signedMessageToKey(message, signature);
	        	    				if (key.toAddress(Constants.NETWORK_PARAMETERS).equals(signAddress)) {
	    	        	    			// signature is verified
	    	        	    			String[] res = new String[4];
	    	        	    			res[0] = url1;
	    	        	    			res[1] = url2;
	    	        	    			res[2] = hash1;
	    	        	    			res[3] = hash2;
	    	        	    			return res;
	        	    				}
	        	    			}
	        	    			catch (SignatureException e) {
	        	    				// Invalid signature
	        	    			}
	        	    		}
	        	    	}
	        	    }
	        	} catch (MalformedURLException e) {
	        	} catch (IOException e) {
	    		} catch (AddressFormatException e) {
	        	}
	        	finally {
	        		if (in != null) {
	        			try {
	        				in.close();
	        			}
	        			catch (IOException e) {
	        			}
	        		}
	        	}
    			
    			return null;
    		}
    		
    		public void onPreExecute() {
    			fDisableUI = true;
    			updateState();
    		}
    		
    		public void onPostExecute(String[] res) {
    			String mainUrl   = null;
    			String patchUrl  = null;
    			String mainHash  = "";
    			String patchHash = "";
    			
    			if (res != null && res.length == 4) {
	    			mainUrl   = res[0];
	    			patchUrl  = res[1];
	    			mainHash  = res[2];
	    			patchHash = res[3];
    			}
    			
    			((WalletApplication)getApplication()).getConfiguration().setObbDownloadInformation(
    					mainUrl,
    					patchUrl,
    					mainHash,
    					patchHash);
    			
    			fDisableUI = false;
    			updateState();
    			
    			startDownload();
    		}
    		
    		
    	};
    	
    	
    	fetchInformation.execute(Constants.BLOCKCHAIN_URL, new Long(0));
    }
    
    private void startDownload() {
    	DownloadCompleteReceiver.startDownload(this);

    	/*final Intent serviceIntent = new Intent(this, BlockchainServiceImpl.class);		
		this.startService(serviceIntent);
		*/
		updateState();
    }
    
    

}

