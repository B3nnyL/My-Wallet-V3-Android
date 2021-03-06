package piuk.blockchain.android.data.websocket;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import info.blockchain.wallet.api.PersistentUrls;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.concurrent.TimeUnit;

import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.ObservableSource;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import piuk.blockchain.android.R;
import piuk.blockchain.android.data.datamanagers.PayloadDataManager;
import piuk.blockchain.android.data.rxjava.IgnorableDefaultObserver;
import piuk.blockchain.android.data.rxjava.RxUtil;
import piuk.blockchain.android.ui.balance.BalanceFragment;
import piuk.blockchain.android.ui.customviews.ToastCustom;
import piuk.blockchain.android.ui.home.MainActivity;
import piuk.blockchain.android.util.MonetaryUtil;
import piuk.blockchain.android.util.NotificationsUtil;


@SuppressWarnings("WeakerAccess")
class WebSocketHandler extends WebSocketListener {

    private static final String TAG = WebSocketHandler.class.getSimpleName();

    private final static long RETRY_INTERVAL = 5 * 1000L;
    /**
     * Websocket status code as defined by <a href="http://tools.ietf.org/html/rfc6455#section-7.4">Section
     * 7.4 of RFC 6455</a>
     */
    private static final int STATUS_CODE_NORMAL_CLOSURE = 1000;

    private boolean stoppedDeliberately = false;
    private String[] xpubs;
    private String[] addrs;
    private NotificationManager notificationManager;
    private String guid;
    private HashSet<String> subHashSet = new HashSet<>();
    private HashSet<String> onChangeHashSet = new HashSet<>();
    private PersistentUrls persistentUrls;
    private MonetaryUtil monetaryUtil;
    private Context context;
    private OkHttpClient okHttpClient;
    private WebSocket webSocketConnection;
    private boolean connected;
    private PayloadDataManager payloadDataManager;
    private CompositeDisposable compositeDisposable = new CompositeDisposable();

    public WebSocketHandler(Context context,
                            OkHttpClient okHttpClient,
                            PayloadDataManager payloadDataManager,
                            NotificationManager notificationManager,
                            PersistentUrls persistentUrls,
                            MonetaryUtil monetaryUtil,
                            String guid,
                            String[] xpubs,
                            String[] addrs) {

        this.context = context;
        this.okHttpClient = okHttpClient;
        this.payloadDataManager = payloadDataManager;
        this.notificationManager = notificationManager;
        this.persistentUrls = persistentUrls;
        this.monetaryUtil = monetaryUtil;
        this.guid = guid;
        this.xpubs = xpubs;
        this.addrs = addrs;
    }

    public void subscribeToXpub(String xpub) {
        if (xpub != null && !xpub.isEmpty()) {
            send("{\"op\":\"xpub_sub\", \"xpub\":\"" + xpub + "\"}");
        }
    }

    public void subscribeToAddress(String address) {
        if (address != null && !address.isEmpty()) {
            send("{\"op\":\"addr_sub\", \"addr\":\"" + address + "\"}");
        }
    }

    /**
     * Starts listening for updates to subscribed xpubs and addresses. Will attempt reconnection
     * every 5 seconds if it cannot connect immediately.
     */
    public void start() {
        stop();
        stoppedDeliberately = false;
        connectToWebSocket()
                .doOnError(throwable -> attemptReconnection())
                .subscribe(new IgnorableDefaultObserver<>());
    }

    /**
     * Halts and disconnects the WebSocket service whilst preventing reconnection until {@link
     * #start()} is called
     */
    public void stopPermanently() {
        stoppedDeliberately = true;
        stop();
    }

    private void stop() {
        if (isConnected()) {
            webSocketConnection.close(STATUS_CODE_NORMAL_CLOSURE, "Websocket deliberately stopped");
            webSocketConnection = null;
        }
    }

    private void send(String message) {
        // Make sure each message is only sent once per socket lifetime
        if (!subHashSet.contains(message)) {
            try {
                if (isConnected()) {
                    webSocketConnection.send(message);
                    subHashSet.add(message);
                }
            } catch (Exception e) {
                Log.e(TAG, "send: ", e);
            }
        }
    }

    private void subscribe() {
        if (guid == null) {
            return;
        }
        send("{\"op\":\"wallet_sub\",\"guid\":\"" + guid + "\"}");

        for (String xpub : xpubs) {
            subscribeToXpub(xpub);
        }

        for (String addr : addrs) {
            subscribeToAddress(addr);
        }
    }

    private void attemptReconnection() {
        if (compositeDisposable.size() == 0 && !stoppedDeliberately) {
            compositeDisposable.add(
                    getReconnectionObservable()
                            .subscribe(
                                    value -> Log.d(TAG, "attemptReconnection: " + value),
                                    throwable -> Log.e(TAG, "attemptReconnection: ", throwable)));
        }
    }

    private Observable<Long> getReconnectionObservable() {
        return Observable.interval(RETRY_INTERVAL, TimeUnit.MILLISECONDS)
                .takeUntil((ObservableSource<Object>) aLong -> isConnected())
                .doOnNext(tick -> start());
    }

    private boolean isConnected() {
        return webSocketConnection != null && connected;
    }

    private void updateBalancesAndTransactions() {
        payloadDataManager.updateAllBalances()
                .andThen(payloadDataManager.updateAllTransactions())
                .doOnComplete(this::sendBroadcast)
                .subscribe(new IgnorableDefaultObserver<>());
    }

    private void sendBroadcast() {
        Intent intent = new Intent(BalanceFragment.ACTION_INTENT);
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
    }

    private void startWebSocket() {
        Request request = new Request.Builder()
                .url(persistentUrls.getCurrentWebsocketUrl())
                .addHeader("Origin", "https://blockchain.info")
                .build();

        webSocketConnection = okHttpClient.newWebSocket(request, this);
    }

    @Override
    public void onOpen(WebSocket webSocket, Response response) {
        super.onOpen(webSocket, response);
        connected = true;
        compositeDisposable.clear();
        subscribe();
    }

    @Override
    public void onMessage(WebSocket webSocket, String text) {
        super.onMessage(webSocket, text);
        if (payloadDataManager.getWallet() != null) {
            JSONObject jsonObject;
            try {
                jsonObject = new JSONObject(text);
                attemptParseMessage(text, jsonObject);
            } catch (JSONException je) {
                Log.e(TAG, "onTextMessage: ", je);
            }
        } else {
            // Ignore content and broadcast anyway so that SwipeToReceive can update
            sendBroadcast();
        }
    }

    @Override
    public void onClosed(WebSocket webSocket, int code, String reason) {
        super.onClosed(webSocket, code, reason);
        connected = false;
        attemptReconnection();
    }

    @Override
    public void onFailure(WebSocket webSocket, Throwable t, Response response) {
        super.onFailure(webSocket, t, response);
        connected = false;
        attemptReconnection();
    }

    private Completable connectToWebSocket() {
        return Completable.fromCallable(() -> {
            subHashSet.clear();
            startWebSocket();
            return Void.TYPE;
        }).compose(RxUtil.applySchedulersToCompletable());
    }

    private void attemptParseMessage(String message, JSONObject jsonObject) {
        try {
            String op = (String) jsonObject.get("op");
            if (op.equals("utx") && jsonObject.has("x")) {
                JSONObject objX = (JSONObject) jsonObject.get("x");

                long value = 0L;
                long totalValue = 0L;
                String inAddr = null;

                if (objX.has("inputs")) {
                    JSONArray inputArray = (JSONArray) objX.get("inputs");
                    JSONObject inputObj;
                    for (int j = 0; j < inputArray.length(); j++) {
                        inputObj = (JSONObject) inputArray.get(j);
                        if (inputObj.has("prev_out")) {
                            JSONObject prevOutObj = (JSONObject) inputObj.get("prev_out");
                            if (prevOutObj.has("value")) {
                                value = prevOutObj.getLong("value");
                            }
                            if (prevOutObj.has("xpub")) {
                                totalValue -= value;
                            } else if (prevOutObj.has("addr")) {
                                if (payloadDataManager.getWallet().containsLegacyAddress((String) prevOutObj.get("addr"))) {
                                    totalValue -= value;
                                } else if (inAddr == null) {
                                    inAddr = (String) prevOutObj.get("addr");
                                }
                            }
                        }
                    }
                }

                if (objX.has("out")) {
                    JSONArray outArray = (JSONArray) objX.get("out");
                    JSONObject outObj;
                    for (int j = 0; j < outArray.length(); j++) {
                        outObj = (JSONObject) outArray.get(j);
                        if (outObj.has("value")) {
                            value = outObj.getLong("value");
                        }
                        if (outObj.has("xpub")) {
                            totalValue += value;
                        } else if (outObj.has("addr")) {
                            if (payloadDataManager.getWallet().containsLegacyAddress((String) outObj.get("addr"))) {
                                totalValue += value;
                            }
                        }
                    }
                }

                String title = context.getString(R.string.app_name);
                if (totalValue > 0L) {
                    String marquee = context.getString(R.string.received_bitcoin) + " " + monetaryUtil.getBTCFormat().format((double) totalValue / 1e8) + " BTC";
                    String text = marquee;
                    if (totalValue > 0) {
                        text += " from " + inAddr;
                    }

                    triggerNotification(title, marquee, text);
                }

                updateBalancesAndTransactions();

            } else if (op.equals("on_change")) {
                final String localChecksum = payloadDataManager.getPayloadChecksum();

                boolean isSameChecksum = false;
                if (jsonObject.has("checksum")) {
                    final String remoteChecksum = (String) jsonObject.get("checksum");
                    isSameChecksum = remoteChecksum.equals(localChecksum);
                }

                if (!onChangeHashSet.contains(message) && !isSameChecksum) {
                    // Remote update to wallet data detected
                    if (payloadDataManager.getTempPassword() != null) {
                        // Download changed payload
                        //noinspection ThrowableResultOfMethodCallIgnored
                        downloadChangedPayload().blockingGet();
                        showToast().subscribeOn(AndroidSchedulers.mainThread())
                                .subscribe(new IgnorableDefaultObserver<>());
                    }

                    onChangeHashSet.add(message);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "attemptParseMessage: ", e);
        }
    }

    private Completable showToast() {
        return Completable.fromRunnable(
                () -> ToastCustom.makeText(
                        context,
                        context.getString(R.string.wallet_updated),
                        ToastCustom.LENGTH_SHORT,
                        ToastCustom.TYPE_GENERAL));
    }

    private Completable downloadChangedPayload() {
        return Completable.fromCallable(() -> {
            payloadDataManager.initializeAndDecrypt(
                    payloadDataManager.getWallet().getSharedKey(),
                    payloadDataManager.getWallet().getGuid(),
                    payloadDataManager.getTempPassword()).subscribe(this::updateBalancesAndTransactions);
            return Void.TYPE;
        }).compose(RxUtil.applySchedulersToCompletable());
    }

    private void triggerNotification(String title, String marquee, String text) {
        new NotificationsUtil(context, notificationManager).setNotification(
                title,
                marquee,
                text,
                R.drawable.ic_notification_white,
                R.drawable.ic_notification_white,
                MainActivity.class,
                1000);
    }

}
