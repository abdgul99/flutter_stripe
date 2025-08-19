package com.flutter.stripe

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.annotation.NonNull
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.uimanager.DisplayMetricsHolder
import com.facebook.react.uimanager.ThemedReactContext
import com.google.android.material.internal.ThemeEnforcement
import com.reactnativestripesdk.*
import com.reactnativestripesdk.addresssheet.AddressSheetViewManager
import com.reactnativestripesdk.pushprovisioning.AddToWalletButtonManager
import com.reactnativestripesdk.utils.getIntOrNull
import com.reactnativestripesdk.utils.getValOr
import com.stripe.android.model.PaymentMethodCreateParams
import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.*
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import org.jitsi.meet.sdk.*
import org.json.JSONObject
import java.net.URL
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

/** JitsiMeetPlugin */
class JitsiMeetPlugin: FlutterPlugin, MethodCallHandler, ActivityAware {
  private lateinit var methodChannel : MethodChannel
  private lateinit var eventChannel: EventChannel
  private val eventStreamHandler = JitsiMeetEventStreamHandler.instance
  private var activity: Activity? = null

  override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
    methodChannel = MethodChannel(flutterPluginBinding.binaryMessenger, "flutter.stripe/jitsi_meet_flutter_sdk")
    methodChannel.setMethodCallHandler(this)

    eventChannel = EventChannel(flutterPluginBinding.binaryMessenger, "flutter.stripe/jitsi_meet_flutter_sdk_events")
    eventChannel.setStreamHandler(eventStreamHandler)
  }

  override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
    when (call.method) {
      "getPlatformVersion" -> {result.success("Android ${android.os.Build.VERSION.RELEASE}")}
      "join" -> join(call, result)
      "hangUp" -> hangUp(call, result)
      "setAudioMuted" -> setAudioMuted(call, result)
      "setVideoMuted" -> setVideoMuted(call, result)
      "sendEndpointTextMessage" -> sendEndpointTextMessage(call, result)
      "toggleScreenShare" -> toggleScreenShare(call, result)
      "openChat" -> openChat(call, result)
      "sendChatMessage" -> sendChatMessage(call, result)
      "closeChat" -> closeChat(call, result)
      "retrieveParticipantsInfo" -> retrieveParticipantsInfo(call, result)
      "enterPiP" -> enterPiP(call, result)
      else -> result.notImplemented()
    }
  }

  override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
    methodChannel.setMethodCallHandler(null)
  }

  override fun onDetachedFromActivity() {
    this.activity = null
  }

  override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
    onAttachedToActivity(binding)
  }

  override fun onAttachedToActivity(binding: ActivityPluginBinding) {
    this.activity = binding.activity
  }

  override fun onDetachedFromActivityForConfigChanges() {
    onDetachedFromActivity()
  }
  private fun join(call: MethodCall, result: Result) {
    val serverURL = if (call.argument<String?>("serverURL") != null) URL(call.argument<String?>("serverURL")) else null
    val room: String? = call.argument("room")
    val token: String? = call.argument("token")
    val featureFlags = call.argument<HashMap<String, Any?>>("featureFlags")
    val configOverrides = call.argument<HashMap<String, Any?>>("configOverrides")
    val rawUserInfo = call.argument<HashMap<String, String?>>("userInfo")
    val displayName = rawUserInfo?.get("displayName")
    val email = rawUserInfo?.get("email")
    val avatar = if (rawUserInfo?.get("avatar") != null) URL(rawUserInfo.get("avatar")) else null
    val userInfo = JitsiMeetUserInfo().apply {
      if (displayName != null) this.displayName = displayName
      if (email != null) this.email = email
      if (avatar != null) this.avatar = avatar
    }

    val options = JitsiMeetConferenceOptions.Builder().run {
      if (serverURL != null) setServerURL(serverURL)
      if (room != null) setRoom(room)
      if (token != null) setToken(token)

      configOverrides?.forEach { (key, value) ->
        when (value) {
          is Boolean -> setConfigOverride(key, value)
          is Int -> setConfigOverride(key, value)
          is Map<*, *> -> {
            val bundle = Bundle()
            value.forEach { (k, v) ->
              when (v) {
                is Boolean -> bundle.putBoolean(k.toString(), v)
                is Int -> bundle.putInt(k.toString(), v)
                is Long -> bundle.putLong(k.toString(), v)
                is Double -> bundle.putDouble(k.toString(), v)
                is Float -> bundle.putFloat(k.toString(), v)
                is String -> bundle.putString(k.toString(), v)
                else -> bundle.putString(k.toString(), v.toString())
              }
            }
            setConfigOverride(key, bundle)
          }
          is Array<*> -> setConfigOverride(key, value as Array<out String>)
          is List<*> -> {
            if (value.isNotEmpty() && value[0] is Map<*, *>) {
              val bundles = ArrayList<Bundle>()
              for (map in value) {
                val bundle = Bundle()
                (map as Map<*, *>).forEach { (k, v) ->
                  bundle.putString(k.toString(), v.toString())
                }
                bundles.add(bundle)
              }
              setConfigOverride(key, bundles)
            } else if (value.isNotEmpty() && value[0] is String) {
              val stringArray = value.map { it.toString() }.toTypedArray()
              setConfigOverride(key, stringArray)
            } else {
              setConfigOverride(key, value.toString())
            }
          }
          else -> setConfigOverride(key, value.toString())
        }
      }
      featureFlags?.forEach { (key, value) ->
        when (value) {
          is Boolean -> setFeatureFlag(key, value)
          is Int -> setFeatureFlag(key, value)
          else -> setFeatureFlag(key, value.toString())
        }
      }
      if (userInfo != null) setUserInfo(userInfo)
      build()
    }

    WrapperJitsiMeetActivity.launch(activity!!, options)
    result.success("Successfully joined meeting $room")
  }

  private fun hangUp(call: MethodCall, result: Result) {
    val hangUpBroadcastIntent = BroadcastIntentHelper.buildHangUpIntent();
    LocalBroadcastManager.getInstance(activity!!.applicationContext).sendBroadcast(hangUpBroadcastIntent)
    result.success("Succesfullly hung up")
  }

  private fun setAudioMuted(call: MethodCall, result: Result) {
    val muted = call.argument<Boolean>("muted") ?: false
    val audioMuteBroadcastIntent: Intent = BroadcastIntentHelper.buildSetAudioMutedIntent(muted)
    LocalBroadcastManager.getInstance(activity!!.applicationContext).sendBroadcast(audioMuteBroadcastIntent)
    result.success("Successfully set audio $muted")
  }

  private fun setVideoMuted(call: MethodCall, result: Result) {
    val muted = call.argument<Boolean>("muted") ?: false
    val videoMuteBroadcastIntent: Intent = BroadcastIntentHelper.buildSetVideoMutedIntent(muted)
    LocalBroadcastManager.getInstance(activity!!.applicationContext).sendBroadcast(videoMuteBroadcastIntent)
    result.success("Successfully set video $muted")
  }

  private fun sendEndpointTextMessage(call: MethodCall, result: Result) {
    val to = call.argument<String?>("to")
    val message = call.argument<String>("message")
    val sendEndpointTextMessageBroadcastIntent: Intent = BroadcastIntentHelper.buildSendEndpointTextMessageIntent(to, message)
    LocalBroadcastManager.getInstance(activity!!.applicationContext).sendBroadcast(sendEndpointTextMessageBroadcastIntent)
    result.success("Successfully send endpoint text message $to")
  }

  private fun toggleScreenShare(call: MethodCall, result: Result) {
    val enabled = call.argument<Boolean>("enabled") ?: false
    val toggleScreenShareIntent: Intent = BroadcastIntentHelper.buildToggleScreenShareIntent(enabled)
    LocalBroadcastManager.getInstance(activity!!.applicationContext).sendBroadcast(toggleScreenShareIntent)
    result.success("Successfully toggled screen share $enabled")
  }

  private fun openChat(call: MethodCall, result: Result) {
    val to = call.argument<String?>("to")
    val openChatIntent: Intent = BroadcastIntentHelper.buildOpenChatIntent(to)
    LocalBroadcastManager.getInstance(activity!!.applicationContext).sendBroadcast(openChatIntent)
    result.success("Successfully opened chat $to")
  }

  private fun sendChatMessage(call: MethodCall, result: Result) {
    val to = call.argument<String?>("to")
    val message = call.argument<String>("message")
    val sendChatMessageIntent: Intent = BroadcastIntentHelper.buildSendChatMessageIntent(to, message)
    LocalBroadcastManager.getInstance(activity!!.applicationContext).sendBroadcast(sendChatMessageIntent)
    result.success("Successfully sent chat message $to")

  }

  private fun closeChat(call: MethodCall, result: Result) {
    val closeChatIntent: Intent = BroadcastIntentHelper.buildCloseChatIntent()
    LocalBroadcastManager.getInstance(activity!!.applicationContext).sendBroadcast(closeChatIntent)
    result.success("Successfully closed chat")
  }

  private fun retrieveParticipantsInfo(call: MethodCall, result: Result) {
    val retrieveParticipantsInfoIntent: Intent = Intent("org.jitsi.meet.RETRIEVE_PARTICIPANTS_INFO");
    LocalBroadcastManager.getInstance(activity!!.applicationContext).sendBroadcast(retrieveParticipantsInfoIntent)
    result.success("Successfully retrieved participants info")
  }

  private fun enterPiP(call: MethodCall, result: Result) {
    val enterPiPIntent = Intent("org.jitsi.meet.ENTER_PICTURE_IN_PICTURE");
    LocalBroadcastManager.getInstance(activity!!.applicationContext).sendBroadcast(enterPiPIntent)
    result.success("Successfully entered PiP")
  }
}


/** StripeAndroidPlugin */
class StripeAndroidPlugin : FlutterPlugin, MethodCallHandler, ActivityAware {
  /// The MethodChannel that will the communication between Flutter and native Android
  ///
  /// This local reference serves to register the plugin with the Flutter Engine and unregister it
  /// when the Flutter Engine is detached from the Activity
  private lateinit var channel: MethodChannel
  private var initializationError: String? = null

  lateinit var stripeSdk: StripeSdkModule

  private val jitsiMeetPlugin: JitsiMeetPlugin by lazy {
    JitsiMeetPlugin()
  }

  private val stripeSdkCardViewManager: CardFieldViewManager by lazy {
    CardFieldViewManager()
  }

  private val cardFormViewManager: CardFormViewManager by lazy {
    CardFormViewManager()
  }

  private val payButtonViewManager: GooglePayButtonManager by lazy {
    GooglePayButtonManager()
  }

  private val aubecsDebitManager: AuBECSDebitFormViewManager by lazy {
    AuBECSDebitFormViewManager()
  }

  private val addressSheetFormViewManager: AddressSheetViewManager by lazy {
    AddressSheetViewManager()
  }

  override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
    DisplayMetricsHolder.initDisplayMetricsIfNotInitialized(flutterPluginBinding.applicationContext)

    channel = MethodChannel(flutterPluginBinding.binaryMessenger, "flutter.stripe/payments", JSONMethodCodec.INSTANCE)
    channel.setMethodCallHandler(this)
    flutterPluginBinding
      .platformViewRegistry
      .registerViewFactory("flutter.stripe/card_field", StripeSdkCardPlatformViewFactory(flutterPluginBinding, stripeSdkCardViewManager) { stripeSdk })
    flutterPluginBinding
      .platformViewRegistry
      .registerViewFactory("flutter.stripe/card_form_field", StripeSdkCardFormPlatformViewFactory(flutterPluginBinding, cardFormViewManager) { stripeSdk })
    flutterPluginBinding
      .platformViewRegistry
      .registerViewFactory("flutter.stripe/google_pay_button", StripeSdkGooglePayButtonPlatformViewFactory(flutterPluginBinding, payButtonViewManager) { stripeSdk })
    flutterPluginBinding
      .platformViewRegistry
      .registerViewFactory("flutter.stripe/aubecs_form_field", StripeAubecsDebitPlatformViewFactory(flutterPluginBinding, aubecsDebitManager){stripeSdk})
    flutterPluginBinding
      .platformViewRegistry
      .registerViewFactory("flutter.stripe/add_to_wallet", StripeAddToWalletPlatformViewFactory(flutterPluginBinding, AddToWalletButtonManager(flutterPluginBinding.applicationContext)){stripeSdk})
    flutterPluginBinding.platformViewRegistry.registerViewFactory("flutter.stripe/address_sheet", StripeAddressSheetPlatformViewFactory(flutterPluginBinding, addressSheetFormViewManager ){stripeSdk})


    jitsiMeetPlugin.onAttachedToEngine(flutterPluginBinding)
  }

  override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
    if (initializationError != null || !this::stripeSdk.isInitialized) {
      result.error(
        "flutter_stripe initialization failed",
        """The plugin failed to initialize:
${initializationError ?: "Stripe SDK did not initialize."}
Please make sure you follow all the steps detailed inside the README: https://github.com/flutter-stripe/flutter_stripe#android
If you continue to have trouble, follow this discussion to get some support https://github.com/flutter-stripe/flutter_stripe/discussions/538""",
        null
      )
      return
    }
    when (call.method) {
      "initialise" -> {
        stripeSdk.initialise(
          params = ReadableMap(call.arguments as JSONObject),
          promise = Promise(result),
        )
      }
      "createPaymentMethod" -> stripeSdk.createPaymentMethod(
        data = call.requiredArgument("data"),
        options = call.requiredArgument("options"),
        promise = Promise(result)
      )
      "createTokenForCVCUpdate" -> stripeSdk.createTokenForCVCUpdate(
        cvc = call.requiredArgument("cvc"),
        promise = Promise(result)
      )
      "confirmSetupIntent" -> stripeSdk.confirmSetupIntent(
        setupIntentClientSecret = call.requiredArgument("setupIntentClientSecret"),
        params = call.requiredArgument("params"),
        options = call.requiredArgument("options"),
        promise = Promise(result)
      )
      "handleNextAction" -> stripeSdk.handleNextAction(
        paymentIntentClientSecret = call.requiredArgument("paymentIntentClientSecret"),
        promise = Promise(result)
      )
      "handleNextActionForSetup" -> stripeSdk.handleNextActionForSetup(
        setupIntentClientSecret = call.requiredArgument("setupIntentClientSecret"),
        promise = Promise(result)
      )

      "confirmPayment" -> stripeSdk.confirmPayment(
        paymentIntentClientSecret = call.requiredArgument("paymentIntentClientSecret"),
        params = call.optionalArgument("params"),
        options = call.requiredArgument("options"),
        promise = Promise(result)
      )
      "retrievePaymentIntent" -> stripeSdk.retrievePaymentIntent(
        clientSecret = call.requiredArgument("clientSecret"),
        promise = Promise(result)
      )
      "retrieveSetupIntent" -> stripeSdk.retrieveSetupIntent(
        clientSecret = call.requiredArgument("clientSecret"),
        promise = Promise(result)
      )
      "initPaymentSheet" -> stripeSdk.initPaymentSheet(
        params = call.requiredArgument("params"),
        promise = Promise(result)
      )
      "presentPaymentSheet" -> stripeSdk.presentPaymentSheet(
        options = call.requiredArgument("options"),
        promise = Promise(result)
      )
      "confirmPaymentSheetPayment" -> stripeSdk.confirmPaymentSheetPayment(
        promise = Promise(result)
      )
      "createToken" -> stripeSdk.createToken(
        promise = Promise(result),
        params = call.requiredArgument("params")
      )
      "dangerouslyUpdateCardDetails" -> {
        stripeSdkCardViewManager.setCardDetails(
          value = call.requiredArgument("params"),
          reactContext = ThemedReactContext(stripeSdk.reactContext, channel) { stripeSdk }
        )
        result.success(null)
      }
      "collectBankAccount" -> stripeSdk.collectBankAccount(
        isPaymentIntent = call.requiredArgument("isPaymentIntent"),
        clientSecret = call.requiredArgument("clientSecret"),
        params = call.requiredArgument("params"),
        promise = Promise(result)
      )
      "verifyMicrodeposits" -> stripeSdk.verifyMicrodeposits(
        isPaymentIntent = call.requiredArgument("isPaymentIntent"),
        clientSecret = call.requiredArgument("clientSecret"),
        params = call.requiredArgument("params"),
        promise = Promise(result)
      )
      "isCardInWallet" -> stripeSdk.isCardInWallet(
        params = call.requiredArgument("params"),
        promise = Promise(result)
      )
      "canAddCardToWallet" -> stripeSdk.canAddCardToWallet(
        params = call.requiredArgument("params"),
        promise = Promise(result)
      )
      "collectBankAccountToken" -> stripeSdk.collectBankAccountToken(
        clientSecret = call.requiredArgument("clientSecret"),
        promise = Promise(result)
      )
      "collectFinancialConnectionsAccounts" -> stripeSdk.collectFinancialConnectionsAccounts(
        clientSecret = call.requiredArgument("clientSecret"),
        promise = Promise(result)
      )
      "resetPaymentSheetCustomer" -> stripeSdk.resetPaymentSheetCustomer(
        promise = Promise(result)
      )
      "intentCreationCallback" -> stripeSdk.intentCreationCallback(
        params = call.requiredArgument("params"),
        promise = Promise(result)
      )
      "createPlatformPayPaymentMethod" -> stripeSdk.createPlatformPayPaymentMethod(
        params = call.requiredArgument("params"),
        usesDeprecatedTokenFlow = call.requiredArgument("usesDeprecatedTokenFlow"),
        promise = Promise(result)
      )
      "isPlatformPaySupported" -> stripeSdk.isPlatformPaySupported(
        params = call.optionalArgument("params"),
        promise = Promise(result)
      )
      "confirmPlatformPay" -> stripeSdk.confirmPlatformPay(
        clientSecret = call.requiredArgument("clientSecret"),
        params = call.requiredArgument("params"),
        isPaymentIntent = call.requiredArgument("isPaymentIntent"),
        promise = Promise(result)
      )
      "addListener" -> {
        stripeSdk.addListener(eventName = call.requiredArgument("eventName"))
        result.success("OK")
      }
      "removeListener" -> {
        stripeSdk.removeListeners(count = call.requiredArgument("count"))
        result.success("OK")
      }
      "initCustomerSheet" -> {
        stripeSdk.initCustomerSheet(
          params = call.requiredArgument("params"),
          customerAdapterOverrides = call.requiredArgument("customerAdapterOverrides"),
          promise = Promise(result)
        )
      }
      "presentCustomerSheet" -> {
        stripeSdk.presentCustomerSheet(
          params = call.requiredArgument("params"),
          promise = Promise(result)
        )
      }
      "retrieveCustomerSheetPaymentOptionSelection" -> {
        stripeSdk.retrieveCustomerSheetPaymentOptionSelection(
          promise = Promise(result)
        )
      }
      "customerAdapterFetchPaymentMethodsCallback" -> {
        stripeSdk.customerAdapterFetchPaymentMethodsCallback(
          paymentMethodJsonObjects = call.requiredArgument("paymentMethodJsonObjects"),
          promise = Promise(result)
        )
      }
      "customerAdapterAttachPaymentMethodCallback" -> {
        stripeSdk.customerAdapterAttachPaymentMethodCallback(
          paymentMethodJson = call.requiredArgument("paymentMethodJson"),
          promise = Promise(result)
        )
      }
      "customerAdapterDetachPaymentMethodCallback" -> {
        stripeSdk.customerAdapterDetachPaymentMethodCallback(
          paymentMethodJson = call.requiredArgument("paymentMethodJson"),
          promise = Promise(result)
        )
      }
      "customerAdapterSetSelectedPaymentOptionCallback" -> {
        stripeSdk.customerAdapterSetSelectedPaymentOptionCallback(
          promise = Promise(result)
        )
      }
      "customerAdapterFetchSelectedPaymentOptionCallback" -> {
        stripeSdk.customerAdapterFetchSelectedPaymentOptionCallback(
          paymentOption = call.optionalArgument("paymentOption"),
          promise = Promise(result)
        )
      }
      "customerAdapterSetupIntentClientSecretForCustomerAttachCallback" -> {
        stripeSdk.customerAdapterSetupIntentClientSecretForCustomerAttachCallback(
          clientSecret = call.requiredArgument("clientSecret"),
          promise = Promise(result)
        )
      }
      else -> jitsiMeetPlugin.onMethodCall(call, result)
    }
  }


  override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
    channel.setMethodCallHandler(null)
    jitsiMeetPlugin.onDetachedFromEngine(binding)
  }

  @SuppressLint("RestrictedApi")
  override fun onAttachedToActivity(binding: ActivityPluginBinding) {
    when {
      binding.activity !is FlutterFragmentActivity -> {
        initializationError =
          "Your Main Activity ${binding.activity.javaClass} is not a subclass FlutterFragmentActivity."
      }
      !ThemeEnforcement.isAppCompatTheme(binding.activity) -> {
        initializationError =
          "Your theme isn't set to use Theme.AppCompat or Theme.MaterialComponents."
      }
      else -> {
        val context = ReactApplicationContext(binding, channel) { stripeSdk }
        stripeSdk = StripeSdkModule(context)
      }
    }
    jitsiMeetPlugin.onAttachedToActivity(binding)
  }

  override fun onDetachedFromActivityForConfigChanges() {
    jitsiMeetPlugin.onDetachedFromActivityForConfigChanges()
  }

  override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
    jitsiMeetPlugin.onReattachedToActivityForConfigChanges(binding)
  }

  override fun onDetachedFromActivity() {
    jitsiMeetPlugin.onDetachedFromActivity()
  }
}

private inline fun <reified T> MethodCall.optionalArgument(key: String): T? {
  val value = argument<T>(key)
  if (value == JSONObject.NULL)
    return null
  if (T::class.java == ReadableMap::class.java) {
    return ReadableMap(argument<JSONObject>(key) ?: JSONObject()) as T
  }
  return value
}

private inline fun <reified T> MethodCall.requiredArgument(key: String): T {
  if (T::class.java == ReadableMap::class.java) {
    return ReadableMap(argument<JSONObject>(key) ?: error("Required parameter $key not set")) as T
  }
  return argument<T>(key) ?: error("Required parameter $key not set")
}


fun CardFormViewManager.getCardViewInstance(): CardFormView? {
  val stripeSdkModule: StripeSdkModule? = reactContextRef?.getNativeModule(StripeSdkModule::class.java)
  return stripeSdkModule?.cardFormView
}


fun CardFieldViewManager.getCardViewInstance(): CardFieldView? {
  val stripeSdkModule: StripeSdkModule? = reactContextRef?.getNativeModule(StripeSdkModule::class.java)
  return stripeSdkModule?.cardFieldView
}

fun CardFieldViewManager.setCardDetails(value: ReadableMap, reactContext: ThemedReactContext) {
  val number = getValOr(value, "number", null)
  val expirationYear = getIntOrNull(value, "expirationYear")
  val expirationMonth = getIntOrNull(value, "expirationMonth")
  val cvc = getValOr(value, "cvc", null)

  val cardViewInstance = getCardViewInstance() ?: createViewInstance(reactContext)
  cardViewInstance.cardParams = PaymentMethodCreateParams.Card.Builder()
    .setNumber(number)
    .setCvc(cvc)
    .setExpiryMonth(expirationMonth)
    .setExpiryYear(expirationYear)
    .build()
}