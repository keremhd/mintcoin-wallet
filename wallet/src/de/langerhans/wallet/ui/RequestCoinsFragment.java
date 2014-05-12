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

package de.langerhans.wallet.ui;

import java.math.BigInteger;
import java.util.LinkedList;
import java.util.List;

import javax.annotation.CheckForNull;

import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.nfc.NfcManager;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.app.LoaderManager;
import android.support.v4.app.LoaderManager.LoaderCallbacks;
import android.support.v4.app.ShareCompat.IntentBuilder;
import android.support.v4.content.Loader;
import android.text.ClipboardManager;
import android.text.SpannableStringBuilder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;

import com.actionbarsherlock.app.SherlockFragment;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuInflater;
import com.actionbarsherlock.view.MenuItem;
import com.actionbarsherlock.widget.ShareActionProvider;
import com.google.bitcoin.core.Address;
import com.google.bitcoin.core.ECKey;
import com.google.bitcoin.core.Wallet;
import com.google.bitcoin.script.ScriptBuilder;
import com.google.bitcoin.uri.BitcoinURI;

import de.langerhans.wallet.AddressBookProvider;
import de.langerhans.wallet.Configuration;
import de.langerhans.wallet.Constants;
import de.langerhans.wallet.ExchangeRatesProvider;
import de.langerhans.wallet.ExchangeRatesProvider.ExchangeRate;
import de.langerhans.wallet.WalletApplication;
import de.langerhans.wallet.offline.AcceptBluetoothService;
import de.langerhans.wallet.util.BitmapFragment;
import de.langerhans.wallet.util.Bluetooth;
import de.langerhans.wallet.util.Nfc;
import de.langerhans.wallet.util.PaymentProtocol;
import de.langerhans.wallet.util.Qr;
import de.langerhans.wallet.R;

/**
 * @author Andreas Schildbach
 */
public final class RequestCoinsFragment extends SherlockFragment
{
	private AbstractBindServiceActivity activity;
	private WalletApplication application;
	private Configuration config;
	private Wallet wallet;
	private NfcManager nfcManager;
	private LoaderManager loaderManager;
	private ClipboardManager clipboardManager;
	private ShareActionProvider shareActionProvider;
	@CheckForNull
	private BluetoothAdapter bluetoothAdapter;

	private ImageView qrView;
	private Bitmap qrCodeBitmap;
	private Spinner addressView;
	private CheckBox acceptBluetoothPaymentView;
	private TextView initiateRequestView;

	@CheckForNull
	private String bluetoothMac;
	@CheckForNull
	private Intent bluetoothServiceIntent;

	private static final int REQUEST_CODE_ENABLE_BLUETOOTH = 0;

	private CurrencyCalculatorLink amountCalculatorLink;

	private static final int ID_RATE_LOADER = 0;

	private static boolean ENABLE_BLUETOOTH_LISTENING = Build.VERSION.SDK_INT >= Constants.SDK_JELLY_BEAN_MR2;

	private static final Logger log = LoggerFactory.getLogger(RequestCoinsFragment.class);

	private final LoaderCallbacks<Cursor> rateLoaderCallbacks = new LoaderManager.LoaderCallbacks<Cursor>()
	{
		@Override
		public Loader<Cursor> onCreateLoader(final int id, final Bundle args)
		{
			return new ExchangeRateLoader(activity, config);
		}

		@Override
		public void onLoadFinished(final Loader<Cursor> loader, final Cursor data)
		{
			if (data != null && data.getCount() > 0)
			{
				data.moveToFirst();
				final ExchangeRate exchangeRate = ExchangeRatesProvider.getExchangeRate(data);

				amountCalculatorLink.setExchangeRate(exchangeRate);
				updateView();
			}
		}

		@Override
		public void onLoaderReset(final Loader<Cursor> loader)
		{
		}
	};

	@Override
	public void onAttach(final Activity activity)
	{
		super.onAttach(activity);

		this.activity = (AbstractBindServiceActivity) activity;
		this.application = (WalletApplication) activity.getApplication();
		this.config = application.getConfiguration();
		this.wallet = application.getWallet();
		this.loaderManager = getLoaderManager();
		this.nfcManager = (NfcManager) activity.getSystemService(Context.NFC_SERVICE);
		this.clipboardManager = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
		this.bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
	}

	
	@Override
	public View onCreateView(final LayoutInflater inflater, final ViewGroup container, final Bundle savedInstanceState)
	{
		final View view = inflater.inflate(R.layout.request_coins_fragment, container, false);
		
		qrView = (ImageView) view.findViewById(R.id.request_coins_qr);
		qrView.setOnClickListener(new OnClickListener()
		{
			@Override
			public void onClick(final View v)
			{
				BitmapFragment.show(getFragmentManager(), qrCodeBitmap);
			}
		});

		final CurrencyAmountView btcAmountView = (CurrencyAmountView) view.findViewById(R.id.request_coins_amount_btc);
		btcAmountView.setCurrencySymbol(config.getBtcPrefix());
		btcAmountView.setInputPrecision(config.getBtcMaxPrecision());
		btcAmountView.setHintPrecision(config.getBtcPrecision());
		btcAmountView.setShift(config.getBtcShift());

		final CurrencyAmountView localAmountView = (CurrencyAmountView) view.findViewById(R.id.request_coins_amount_local);
		localAmountView.setInputPrecision(Constants.LOCAL_PRECISION);
		localAmountView.setHintPrecision(Constants.LOCAL_PRECISION);
		amountCalculatorLink = new CurrencyCalculatorLink(btcAmountView, localAmountView);

		addressView = (Spinner) view.findViewById(R.id.request_coins_fragment_address);
		final List<ECKey> keys = new LinkedList<ECKey>();
		for (final ECKey key : application.getWallet().getKeys())
			if (!wallet.isKeyRotating(key))
				keys.add(key);
		final WalletAddressesAdapter adapter = new WalletAddressesAdapter(activity, wallet, false);
		adapter.replace(keys);
		addressView.setAdapter(adapter);
		final Address selectedAddress = application.determineSelectedAddress();
		for (int i = 0; i < keys.size(); i++)
		{
			final Address address = keys.get(i).toAddress(Constants.NETWORK_PARAMETERS);
			if (address.equals(selectedAddress))
			{
				addressView.setSelection(i);
				break;
			}
		}

		acceptBluetoothPaymentView = (CheckBox) view.findViewById(R.id.request_coins_accept_bluetooth_payment);
		acceptBluetoothPaymentView.setVisibility(ENABLE_BLUETOOTH_LISTENING && bluetoothAdapter != null ? View.VISIBLE : View.GONE);
		acceptBluetoothPaymentView.setChecked(ENABLE_BLUETOOTH_LISTENING && bluetoothAdapter != null && bluetoothAdapter.isEnabled());
		acceptBluetoothPaymentView.setOnCheckedChangeListener(new OnCheckedChangeListener()
		{
			@Override
			public void onCheckedChanged(final CompoundButton buttonView, final boolean isChecked)
			{
				if (ENABLE_BLUETOOTH_LISTENING && bluetoothAdapter != null && isChecked)
				{
					if (bluetoothAdapter.isEnabled())
					{
						startBluetoothListening();
					}
					else
					{
						// ask for permission to enable bluetooth
						startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_CODE_ENABLE_BLUETOOTH);
					}
				}
				else
				{
					stopBluetoothListening();
				}

				updateView();
			}
		});

		initiateRequestView = (TextView) view.findViewById(R.id.request_coins_fragment_initiate_request);

		return view;
	}
	


	@Override
	public void onViewCreated(final View view, final Bundle savedInstanceState)
	{
		super.onViewCreated(view, savedInstanceState);


		amountCalculatorLink.setExchangeDirection(config.getLastExchangeDirection());
		amountCalculatorLink.requestFocus();
	}

/*	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		// TODO Auto-generated method stub
		super.onActivityCreated(savedInstanceState);
		// don't call in onCreate() because ActionBarSherlock invokes onCreateOptionsMenu() too early
		setHasOptionsMenu(true);
	}*/


	@Override
	public void onResume()
	{
		super.onResume();

		amountCalculatorLink.setListener(new CurrencyAmountView.Listener()
		{
			@Override
			public void changed()
			{
				updateView();
				updateShareIntent();
			}

			@Override
			public void focusChanged(final boolean hasFocus)
			{
			}
		});

		addressView.setOnItemSelectedListener(new OnItemSelectedListener()
		{
			@Override
			public void onItemSelected(final AdapterView<?> parent, final View view, final int position, final long id)
			{
				// ignore layout operations
				if (view == null)
					return;

				updateView();
//				updateShareIntent();
			}

			@Override
			public void onNothingSelected(final AdapterView<?> parent)
			{
			}
		});

		loaderManager.initLoader(ID_RATE_LOADER, null, rateLoaderCallbacks);

		if (ENABLE_BLUETOOTH_LISTENING && bluetoothAdapter != null && bluetoothAdapter.isEnabled() && acceptBluetoothPaymentView.isChecked())
			startBluetoothListening();

		updateView();
	}

	@Override
	public void onDestroyView()
	{
		super.onDestroyView();

		config.setLastExchangeDirection(amountCalculatorLink.getExchangeDirection());
	}

	@Override
	public void onPause()
	{
		loaderManager.destroyLoader(ID_RATE_LOADER);

		Nfc.unpublish(nfcManager, activity);

		amountCalculatorLink.setListener(null);

		addressView.setOnItemSelectedListener(null);

		super.onPause();
	}

	@Override
	public void onActivityResult(final int requestCode, final int resultCode, final Intent data)
	{
		if (requestCode == REQUEST_CODE_ENABLE_BLUETOOTH)
		{
			acceptBluetoothPaymentView.setChecked(resultCode == Activity.RESULT_OK);

			if (resultCode == Activity.RESULT_OK && bluetoothAdapter != null)
				startBluetoothListening();

			if (isResumed())
				updateView();
		}
	}

	private void startBluetoothListening()
	{
		bluetoothMac = Bluetooth.compressMac(bluetoothAdapter.getAddress());

		bluetoothServiceIntent = new Intent(activity, AcceptBluetoothService.class);
		activity.startService(bluetoothServiceIntent);
	}

	private void stopBluetoothListening()
	{
		if (bluetoothServiceIntent != null)
		{
			activity.stopService(bluetoothServiceIntent);
			bluetoothServiceIntent = null;
		}

		bluetoothMac = null;
	}

	@Override
	public void onCreateOptionsMenu(final Menu menu, final MenuInflater inflater)
	{
		inflater.inflate(R.menu.request_coins_fragment_options, menu);

		final MenuItem shareItem = menu.findItem(R.id.request_coins_options_share);
		shareActionProvider = (ShareActionProvider) shareItem.getActionProvider();

		updateShareIntent();

        if (!checkForLocalApp())
            menu.removeItem(2); //The "Request from local" menu entry.

		super.onCreateOptionsMenu(menu, inflater);
	}

    private boolean checkForLocalApp() {
        final Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("mintcoin:DEwTHxztkSDUJEMywbPk5zVUGrupaNRqEy")); //Just for testing; will never be fired.
        PackageManager manager = getActivity().getPackageManager();
        List<ResolveInfo> infos = manager.queryIntentActivities(intent, 0);
        return infos.size() > 0 ? true : false;
    }

    @Override
	public boolean onOptionsItemSelected(final MenuItem item)
	{
		switch (item.getItemId())
		{
			case R.id.request_coins_options_copy:
				handleCopy();
				return true;

			case R.id.request_coins_options_local_app:
				handleLocalApp();
				return true;
		}

		return super.onOptionsItemSelected(item);
	}

	private void handleCopy()
	{
		final String request = determineBitcoinRequestStr(false);
		clipboardManager.setText(request);
		activity.toast(R.string.request_coins_clipboard_msg);
	}

	private void handleLocalApp()
	{
		final Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(determineBitcoinRequestStr(false)));
		startActivity(intent);
		activity.finish();
	}

	private void updateView()
	{
		if (!isResumed())
			return;

		final String bitcoinRequest = determineBitcoinRequestStr(true);
		final byte[] paymentRequest = determinePaymentRequest(true);

		// update qr-code
		final int size = (int) (256 * getResources().getDisplayMetrics().density);
		final String qrContent;
		if (config.getQrPaymentRequestEnabled())
			qrContent = "MINTCOIN:-" + Qr.encodeBinary(paymentRequest);
		else
			qrContent = bitcoinRequest;
		qrCodeBitmap = Qr.bitmap(qrContent, size);
		qrView.setImageBitmap(qrCodeBitmap);

		// update nfc ndef message
		final boolean nfcSuccess;
		if (config.getNfcPaymentRequestEnabled())
			nfcSuccess = Nfc.publishMimeObject(nfcManager, activity, Constants.MIMETYPE_PAYMENTREQUEST, paymentRequest, false);
		else
			nfcSuccess = Nfc.publishUri(nfcManager, activity, bitcoinRequest);

		// update initiate request message
		final SpannableStringBuilder initiateText = new SpannableStringBuilder(getString(R.string.request_coins_fragment_initiate_request_qr));
		if (nfcSuccess)
			initiateText.append(' ').append(getString(R.string.request_coins_fragment_initiate_request_nfc));
		initiateRequestView.setText(initiateText);

		// focus linking
		final int activeAmountViewId = amountCalculatorLink.activeTextView().getId();
		addressView.setNextFocusUpId(activeAmountViewId);
	}

	private void updateShareIntent()
	{
		// update share intent
		final IntentBuilder builder = IntentBuilder.from(activity);
		builder.setText(determineBitcoinRequestStr(false));
		builder.setType("text/plain");
		builder.setChooserTitle(R.string.request_coins_share_dialog_title);
		shareActionProvider.setShareIntent(builder.getIntent());
	}

	private String determineBitcoinRequestStr(final boolean includeBluetoothMac)
	{
		final ECKey key = (ECKey) addressView.getSelectedItem();
		final Address address = key.toAddress(Constants.NETWORK_PARAMETERS);
		final BigInteger amount = amountCalculatorLink.getAmount();

		final StringBuilder uri = new StringBuilder(BitcoinURI.convertToBitcoinURI(address, amount, null, null));
		if (includeBluetoothMac && bluetoothMac != null)
		{
			uri.append(amount == null ? '?' : '&');
			uri.append(Bluetooth.MAC_URI_PARAM).append('=').append(bluetoothMac);
		}
		return uri.toString();
	}

	private byte[] determinePaymentRequest(final boolean includeBluetoothMac)
	{
		final ECKey key = (ECKey) addressView.getSelectedItem();
		final Address address = key.toAddress(Constants.NETWORK_PARAMETERS);

		return PaymentProtocol.createPaymentRequest(amountCalculatorLink.getAmount(), address, null,
				includeBluetoothMac && bluetoothMac != null ? "bt:" + bluetoothMac : null).toByteArray();
	}
}