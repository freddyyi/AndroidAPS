package info.nightscout.androidaps.plugins.pump.danaRS

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.text.format.DateFormat
import androidx.preference.Preference
import dagger.android.HasAndroidInjector
import info.nightscout.androidaps.BuildConfig
import info.nightscout.androidaps.R
import info.nightscout.androidaps.data.DetailedBolusInfo
import info.nightscout.androidaps.data.Profile
import info.nightscout.androidaps.data.PumpEnactResult
import info.nightscout.androidaps.db.Treatment
import info.nightscout.androidaps.events.EventAppExit
import info.nightscout.androidaps.events.EventConfigBuilderChange
import info.nightscout.androidaps.interfaces.*
import info.nightscout.androidaps.logging.AAPSLogger
import info.nightscout.androidaps.logging.LTag
import info.nightscout.androidaps.plugins.bus.RxBusWrapper
import info.nightscout.androidaps.plugins.common.ManufacturerType
import info.nightscout.androidaps.plugins.configBuilder.ConstraintChecker
import info.nightscout.androidaps.plugins.general.actions.defs.CustomAction
import info.nightscout.androidaps.plugins.general.actions.defs.CustomActionType
import info.nightscout.androidaps.plugins.general.overview.events.EventDismissNotification
import info.nightscout.androidaps.plugins.general.overview.events.EventNewNotification
import info.nightscout.androidaps.plugins.general.overview.notifications.Notification
import info.nightscout.androidaps.plugins.pump.common.bolusInfo.DetailedBolusInfoStorage
import info.nightscout.androidaps.plugins.pump.common.defs.PumpType
import info.nightscout.androidaps.plugins.pump.danaR.DanaRFragment
import info.nightscout.androidaps.plugins.pump.danaR.DanaRPump
import info.nightscout.androidaps.plugins.pump.danaR.comm.RecordTypes
import info.nightscout.androidaps.plugins.pump.danaRS.events.EventDanaRSDeviceChange
import info.nightscout.androidaps.plugins.pump.danaRS.services.DanaRSService
import info.nightscout.androidaps.plugins.treatments.TreatmentsPlugin
import info.nightscout.androidaps.utils.*
import info.nightscout.androidaps.utils.resources.ResourceHelper
import info.nightscout.androidaps.utils.sharedPreferences.SP
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import org.json.JSONException
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max

@Singleton
class DanaRSPlugin @Inject constructor(
    injector: HasAndroidInjector,
    aapsLogger: AAPSLogger,
    private val rxBus: RxBusWrapper,
    private val context: Context,
    resourceHelper: ResourceHelper,
    private val constraintChecker: ConstraintChecker,
    private val profileFunction: ProfileFunction,
    private val treatmentsPlugin: TreatmentsPlugin,
    private val sp: SP,
    commandQueue: CommandQueueProvider,
    private val danaRPump: DanaRPump,
    private val detailedBolusInfoStorage: DetailedBolusInfoStorage,
    private val fabricPrivacy: FabricPrivacy,
    private val dateUtil: DateUtil
) : PumpPluginBase(PluginDescription()
    .mainType(PluginType.PUMP)
    .fragmentClass(DanaRFragment::class.java.name)
    .pluginName(R.string.danarspump)
    .shortName(R.string.danarspump_shortname)
    .preferencesId(R.xml.pref_danars)
    .description(R.string.description_pump_dana_rs),
    injector, aapsLogger, resourceHelper, commandQueue
), PumpInterface, DanaRInterface, ConstraintsInterface {

    private val disposable = CompositeDisposable()
    private var danaRSService: DanaRSService? = null
    private var mDeviceAddress = ""
    var mDeviceName = ""
    private var pumpDesc = PumpDescription(PumpType.DanaRS)

    override fun updatePreferenceSummary(pref: Preference) {
        super.updatePreferenceSummary(pref)

        if (pref.key == resourceHelper.gs(R.string.key_danars_name)) {
            val value = sp.getStringOrNull(R.string.key_danars_name, null)
            pref.summary = value
                ?: resourceHelper.gs(R.string.rileylink_error_address_not_set_short)
        }
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(context, DanaRSService::class.java)
        context.bindService(intent, mConnection, Context.BIND_AUTO_CREATE)
        disposable.add(rxBus
            .toObservable(EventAppExit::class.java)
            .observeOn(Schedulers.io())
            .subscribe({ context.unbindService(mConnection) }) { fabricPrivacy.logException(it) }
        )
        disposable.add(rxBus
            .toObservable(EventConfigBuilderChange::class.java)
            .observeOn(Schedulers.io())
            .subscribe { danaRPump.reset() }
        )
        disposable.add(rxBus
            .toObservable(EventDanaRSDeviceChange::class.java)
            .observeOn(Schedulers.io())
            .subscribe({ changePump() }) { fabricPrivacy.logException(it) }
        )
        changePump() // load device name
    }

    override fun onStop() {
        context.unbindService(mConnection)
        disposable.clear()
        super.onStop()
    }

    private val mConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceDisconnected(name: ComponentName) {
            aapsLogger.debug(LTag.PUMP, "Service is disconnected")
            danaRSService = null
        }

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            aapsLogger.debug(LTag.PUMP, "Service is connected")
            val mLocalBinder = service as DanaRSService.LocalBinder
            danaRSService = mLocalBinder.serviceInstance
        }
    }

    fun changePump() {
        mDeviceAddress = sp.getString(R.string.key_danars_address, "")
        mDeviceName = sp.getString(R.string.key_danars_name, "")
        danaRPump.reset()
        commandQueue.readStatus("DeviceChanged", null)
    }

    override fun connect(from: String) {
        aapsLogger.debug(LTag.PUMP, "RS connect from: $from")
        if (danaRSService != null && mDeviceAddress != "" && mDeviceName != "") {
            val success = danaRSService?.connect(from, mDeviceAddress) ?: false
            if (!success) ToastUtils.showToastInUiThread(context, resourceHelper.gs(R.string.rileylink_scanner_ble_not_supported))
        }
    }

    override fun isConnected(): Boolean {
        return danaRSService?.isConnected ?: false
    }

    override fun isConnecting(): Boolean {
        return danaRSService?.isConnecting ?: false
    }

    override fun isHandshakeInProgress(): Boolean {
        return false
    }

    override fun finishHandshaking() {}
    override fun disconnect(from: String) {
        aapsLogger.debug(LTag.PUMP, "RS disconnect from: $from")
        danaRSService?.disconnect(from)
    }

    override fun stopConnecting() {
        danaRSService?.stopConnecting()
    }

    override fun getPumpStatus() {
        danaRSService?.readPumpStatus()
        pumpDesc.basalStep = danaRPump.basalStep
        pumpDesc.bolusStep = danaRPump.bolusStep
    }

    // DanaR interface
    override fun loadHistory(type: Byte): PumpEnactResult {
        return danaRSService?.loadHistory(type) ?: PumpEnactResult(injector).success(false)
    }

    override fun loadEvents(): PumpEnactResult {
        return danaRSService?.loadEvents() ?: PumpEnactResult(injector).success(false)
    }

    override fun setUserOptions(): PumpEnactResult {
        return danaRSService?.setUserSettings() ?: PumpEnactResult(injector).success(false)
    }

    // Constraints interface
    override fun applyBasalConstraints(absoluteRate: Constraint<Double>, profile: Profile): Constraint<Double> {
        absoluteRate.setIfSmaller(aapsLogger, danaRPump.maxBasal, resourceHelper.gs(R.string.limitingbasalratio, danaRPump.maxBasal, resourceHelper.gs(R.string.pumplimit)), this)
        return absoluteRate
    }

    override fun applyBasalPercentConstraints(percentRate: Constraint<Int>, profile: Profile): Constraint<Int> {
        percentRate.setIfGreater(aapsLogger, 0, resourceHelper.gs(R.string.limitingpercentrate, 0, resourceHelper.gs(R.string.itmustbepositivevalue)), this)
        percentRate.setIfSmaller(aapsLogger, pumpDescription.maxTempPercent, resourceHelper.gs(R.string.limitingpercentrate, pumpDescription.maxTempPercent, resourceHelper.gs(R.string.pumplimit)), this)
        return percentRate
    }

    override fun applyBolusConstraints(insulin: Constraint<Double>): Constraint<Double> {
        insulin.setIfSmaller(aapsLogger, danaRPump.maxBolus, resourceHelper.gs(R.string.limitingbolus, danaRPump.maxBolus, resourceHelper.gs(R.string.pumplimit)), this)
        return insulin
    }

    override fun applyExtendedBolusConstraints(insulin: Constraint<Double>): Constraint<Double> {
        return applyBolusConstraints(insulin)
    }

    // Pump interface
    override fun isInitialized(): Boolean {
        return danaRPump.lastConnection > 0 && danaRPump.maxBasal > 0 && danaRPump.isRSPasswordOK
    }

    override fun isSuspended(): Boolean {
        return danaRPump.pumpSuspended
    }

    override fun isBusy(): Boolean {
        return danaRSService?.isConnected ?: false || danaRSService?.isConnecting ?: false
    }

    override fun setNewBasalProfile(profile: Profile): PumpEnactResult {
        val result = PumpEnactResult(injector)
        if (!isInitialized) {
            aapsLogger.error("setNewBasalProfile not initialized")
            val notification = Notification(Notification.PROFILE_NOT_SET_NOT_INITIALIZED, resourceHelper.gs(R.string.pumpNotInitializedProfileNotSet), Notification.URGENT)
            rxBus.send(EventNewNotification(notification))
            result.comment = resourceHelper.gs(R.string.pumpNotInitializedProfileNotSet)
            return result
        } else {
            rxBus.send(EventDismissNotification(Notification.PROFILE_NOT_SET_NOT_INITIALIZED))
        }
        return if (danaRSService?.updateBasalsInPump(profile) != true) {
            val notification = Notification(Notification.FAILED_UDPATE_PROFILE, resourceHelper.gs(R.string.failedupdatebasalprofile), Notification.URGENT)
            rxBus.send(EventNewNotification(notification))
            result.comment = resourceHelper.gs(R.string.failedupdatebasalprofile)
            result
        } else {
            rxBus.send(EventDismissNotification(Notification.PROFILE_NOT_SET_NOT_INITIALIZED))
            rxBus.send(EventDismissNotification(Notification.FAILED_UDPATE_PROFILE))
            val notification = Notification(Notification.PROFILE_SET_OK, resourceHelper.gs(R.string.profile_set_ok), Notification.INFO, 60)
            rxBus.send(EventNewNotification(notification))
            result.success = true
            result.enacted = true
            result.comment = "OK"
            result
        }
    }

    override fun isThisProfileSet(profile: Profile): Boolean {
        if (!isInitialized) return true // TODO: not sure what's better. so far TRUE to prevent too many SMS
        if (danaRPump.pumpProfiles == null) return true // TODO: not sure what's better. so far TRUE to prevent too many SMS
        val basalValues = if (danaRPump.basal48Enable) 48 else 24
        val basalIncrement = if (danaRPump.basal48Enable) 30 * 60 else 60 * 60
        for (h in 0 until basalValues) {
            val pumpValue = danaRPump.pumpProfiles!![danaRPump.activeProfile][h]
            val profileValue = profile.getBasalTimeFromMidnight(h * basalIncrement)
            if (abs(pumpValue - profileValue) > pumpDescription.basalStep) {
                aapsLogger.debug(LTag.PUMP, "Diff found. Hour: $h Pump: $pumpValue Profile: $profileValue")
                return false
            }
        }
        return true
    }

    override fun lastDataTime(): Long {
        return danaRPump.lastConnection
    }

    override fun getBaseBasalRate(): Double {
        return danaRPump.currentBasal
    }

    override fun getReservoirLevel(): Double {
        return danaRPump.reservoirRemainingUnits
    }

    override fun getBatteryLevel(): Int {
        return danaRPump.batteryRemaining
    }

    @Synchronized
    override fun deliverTreatment(detailedBolusInfo: DetailedBolusInfo): PumpEnactResult {
        detailedBolusInfo.insulin = constraintChecker.applyBolusConstraints(Constraint(detailedBolusInfo.insulin)).value()
        return if (detailedBolusInfo.insulin > 0 || detailedBolusInfo.carbs > 0) {
            val preferencesSpeed = sp.getInt(R.string.key_danars_bolusspeed, 0)
            var speed = 12
            when (preferencesSpeed) {
                0 -> speed = 12
                1 -> speed = 30
                2 -> speed = 60
            }
            // RS stores end time for bolus, we need to adjust time
            // default delivery speed is 12 sec/U
            detailedBolusInfo.date = DateUtil.now() + (speed * detailedBolusInfo.insulin * 1000).toLong()
            // clean carbs to prevent counting them as twice because they will picked up as another record
            // I don't think it's necessary to copy DetailedBolusInfo right now for carbs records
            val carbs = detailedBolusInfo.carbs
            detailedBolusInfo.carbs = 0.0
            var carbTime = detailedBolusInfo.carbTime
            if (carbTime == 0) carbTime-- // better set 1 min back to prevents clash with insulin
            detailedBolusInfo.carbTime = 0
            detailedBolusInfoStorage.add(detailedBolusInfo) // will be picked up on reading history
            val t = Treatment()
            t.isSMB = detailedBolusInfo.isSMB
            var connectionOK = false
            if (detailedBolusInfo.insulin > 0 || carbs > 0) connectionOK = danaRSService?.bolus(detailedBolusInfo.insulin, carbs.toInt(), DateUtil.now() + T.mins(carbTime.toLong()).msecs(), t)
                ?: false
            val result = PumpEnactResult(injector)
            result.success = connectionOK && abs(detailedBolusInfo.insulin - t.insulin) < pumpDesc.bolusStep
            result.bolusDelivered = t.insulin
            result.carbsDelivered = detailedBolusInfo.carbs
            if (!result.success) {
                var error = "" + danaRPump.bolusStartErrorCode
                when (danaRPump.bolusStartErrorCode) {
                    0x10 -> error = resourceHelper.gs(R.string.maxbolusviolation)
                    0x20 -> error = resourceHelper.gs(R.string.commanderror)
                    0x40 -> error = resourceHelper.gs(R.string.speederror)
                    0x80 -> error = resourceHelper.gs(R.string.insulinlimitviolation)
                }
                result.comment = String.format(resourceHelper.gs(R.string.boluserrorcode), detailedBolusInfo.insulin, t.insulin, error)
            } else result.comment = resourceHelper.gs(R.string.virtualpump_resultok)
            aapsLogger.debug(LTag.PUMP, "deliverTreatment: OK. Asked: " + detailedBolusInfo.insulin + " Delivered: " + result.bolusDelivered)
            result
        } else {
            val result = PumpEnactResult(injector)
            result.success = false
            result.bolusDelivered = 0.0
            result.carbsDelivered = 0.0
            result.comment = resourceHelper.gs(R.string.danar_invalidinput)
            aapsLogger.error("deliverTreatment: Invalid input")
            result
        }
    }

    override fun stopBolusDelivering() {
        danaRSService?.bolusStop()
    }

    // This is called from APS
    @Synchronized
    override fun setTempBasalAbsolute(absoluteRate: Double, durationInMinutes: Int, profile: Profile, enforceNew: Boolean): PumpEnactResult {
        var result = PumpEnactResult(injector)
        val absoluteAfterConstrain = constraintChecker.applyBasalConstraints(Constraint(absoluteRate), profile).value()
        val doTempOff = baseBasalRate - absoluteAfterConstrain == 0.0
        val doLowTemp = absoluteAfterConstrain < baseBasalRate
        val doHighTemp = absoluteAfterConstrain > baseBasalRate
        if (doTempOff) {
            // If temp in progress
            if (treatmentsPlugin.isTempBasalInProgress) {
                aapsLogger.debug(LTag.PUMP, "setTempBasalAbsolute: Stopping temp basal (doTempOff)")
                return cancelTempBasal(false)
            }
            result.success = true
            result.enacted = false
            result.percent = 100
            result.isPercent = true
            result.isTempCancel = true
            aapsLogger.debug(LTag.PUMP, "setTempBasalAbsolute: doTempOff OK")
            return result
        }
        if (doLowTemp || doHighTemp) {
            var percentRate = java.lang.Double.valueOf(absoluteAfterConstrain / baseBasalRate * 100).toInt()
            percentRate = if (percentRate < 100) Round.ceilTo(percentRate.toDouble(), 10.0).toInt() else Round.floorTo(percentRate.toDouble(), 10.0).toInt()
            if (percentRate > 500) // Special high temp 500/15min
                percentRate = 500
            // Check if some temp is already in progress
            val activeTemp = treatmentsPlugin.getTempBasalFromHistory(System.currentTimeMillis())
            if (activeTemp != null) {
                aapsLogger.debug(LTag.PUMP, "setTempBasalAbsolute: currently running: $activeTemp")
                // Correct basal already set ?
                if (activeTemp.percentRate == percentRate && activeTemp.plannedRemainingMinutes > 4) {
                    if (!enforceNew) {
                        result.success = true
                        result.percent = percentRate
                        result.enacted = false
                        result.duration = activeTemp.plannedRemainingMinutes
                        result.isPercent = true
                        result.isTempCancel = false
                        aapsLogger.debug(LTag.PUMP, "setTempBasalAbsolute: Correct temp basal already set (doLowTemp || doHighTemp)")
                        return result
                    }
                }
            }
            // Convert duration from minutes to hours
            aapsLogger.debug(LTag.PUMP, "setTempBasalAbsolute: Setting temp basal $percentRate% for $durationInMinutes mins (doLowTemp || doHighTemp)")
            result = if (percentRate == 0 && durationInMinutes > 30) {
                setTempBasalPercent(percentRate, durationInMinutes, profile, enforceNew)
            } else {
                // use special APS temp basal call ... 100+/15min .... 100-/30min
                setHighTempBasalPercent(percentRate)
            }
            if (!result.success) {
                aapsLogger.error("setTempBasalAbsolute: Failed to set hightemp basal")
                return result
            }
            aapsLogger.debug(LTag.PUMP, "setTempBasalAbsolute: hightemp basal set ok")
            return result
        }
        // We should never end here
        aapsLogger.error("setTempBasalAbsolute: Internal error")
        result.success = false
        result.comment = "Internal error"
        return result
    }

    @Synchronized
    override fun setTempBasalPercent(percent: Int, durationInMinutes: Int, profile: Profile, enforceNew: Boolean): PumpEnactResult {
        val result = PumpEnactResult(injector)
        var percentAfterConstraint = constraintChecker.applyBasalPercentConstraints(Constraint(percent), profile).value()
        if (percentAfterConstraint < 0) {
            result.isTempCancel = false
            result.enacted = false
            result.success = false
            result.comment = resourceHelper.gs(R.string.danar_invalidinput)
            aapsLogger.error("setTempBasalPercent: Invalid input")
            return result
        }
        if (percentAfterConstraint > pumpDescription.maxTempPercent) percentAfterConstraint = pumpDescription.maxTempPercent
        val now = System.currentTimeMillis()
        val activeTemp = treatmentsPlugin.getTempBasalFromHistory(now)
        if (activeTemp != null && activeTemp.percentRate == percentAfterConstraint && activeTemp.plannedRemainingMinutes > 4 && !enforceNew) {
            result.enacted = false
            result.success = true
            result.isTempCancel = false
            result.comment = resourceHelper.gs(R.string.virtualpump_resultok)
            result.duration = danaRPump.tempBasalRemainingMin
            result.percent = danaRPump.tempBasalPercent
            result.isPercent = true
            aapsLogger.debug(LTag.PUMP, "setTempBasalPercent: Correct value already set")
            return result
        }
        val connectionOK: Boolean
        connectionOK = if (durationInMinutes == 15 || durationInMinutes == 30) {
            danaRSService?.tempBasalShortDuration(percentAfterConstraint, durationInMinutes)
                ?: false
        } else {
            val durationInHours = max(durationInMinutes / 60, 1)
            danaRSService?.tempBasal(percentAfterConstraint, durationInHours) ?: false
        }
        if (connectionOK && danaRPump.isTempBasalInProgress && danaRPump.tempBasalPercent == percentAfterConstraint) {
            result.enacted = true
            result.success = true
            result.comment = resourceHelper.gs(R.string.virtualpump_resultok)
            result.isTempCancel = false
            result.duration = danaRPump.tempBasalRemainingMin
            result.percent = danaRPump.tempBasalPercent
            result.isPercent = true
            aapsLogger.debug(LTag.PUMP, "setTempBasalPercent: OK")
            return result
        }
        result.enacted = false
        result.success = false
        result.comment = resourceHelper.gs(R.string.tempbasaldeliveryerror)
        aapsLogger.error("setTempBasalPercent: Failed to set temp basal")
        return result
    }

    @Synchronized private fun setHighTempBasalPercent(percent: Int): PumpEnactResult {
        val result = PumpEnactResult(injector)
        val connectionOK = danaRSService?.highTempBasal(percent) ?: false
        if (connectionOK && danaRPump.isTempBasalInProgress && danaRPump.tempBasalPercent == percent) {
            result.enacted = true
            result.success = true
            result.comment = resourceHelper.gs(R.string.virtualpump_resultok)
            result.isTempCancel = false
            result.duration = danaRPump.tempBasalRemainingMin
            result.percent = danaRPump.tempBasalPercent
            result.isPercent = true
            aapsLogger.debug(LTag.PUMP, "setHighTempBasalPercent: OK")
            return result
        }
        result.enacted = false
        result.success = false
        result.comment = resourceHelper.gs(R.string.danar_valuenotsetproperly)
        aapsLogger.error("setHighTempBasalPercent: Failed to set temp basal")
        return result
    }

    @Synchronized
    override fun setExtendedBolus(insulin: Double, durationInMinutes: Int): PumpEnactResult {
        var insulinAfterConstraint = constraintChecker.applyExtendedBolusConstraints(Constraint(insulin)).value()
        // needs to be rounded
        val durationInHalfHours = max(durationInMinutes / 30, 1)
        insulinAfterConstraint = Round.roundTo(insulinAfterConstraint, pumpDescription.extendedBolusStep)
        val result = PumpEnactResult(injector)
        val runningEB = treatmentsPlugin.getExtendedBolusFromHistory(System.currentTimeMillis())
        if (runningEB != null && abs(runningEB.insulin - insulinAfterConstraint) < pumpDescription.extendedBolusStep) {
            result.enacted = false
            result.success = true
            result.comment = resourceHelper.gs(R.string.virtualpump_resultok)
            result.duration = danaRPump.extendedBolusRemainingMinutes
            result.absolute = danaRPump.extendedBolusAbsoluteRate
            result.isPercent = false
            result.isTempCancel = false
            aapsLogger.debug(LTag.PUMP, "setExtendedBolus: Correct extended bolus already set. Current: " + danaRPump.extendedBolusAmount + " Asked: " + insulinAfterConstraint)
            return result
        }
        val connectionOK = danaRSService?.extendedBolus(insulinAfterConstraint, durationInHalfHours)
            ?: false
        if (connectionOK && danaRPump.isExtendedInProgress && abs(danaRPump.extendedBolusAbsoluteRate - insulinAfterConstraint) < pumpDescription.extendedBolusStep) {
            result.enacted = true
            result.success = true
            result.comment = resourceHelper.gs(R.string.virtualpump_resultok)
            result.isTempCancel = false
            result.duration = danaRPump.extendedBolusRemainingMinutes
            result.absolute = danaRPump.extendedBolusAbsoluteRate
            result.bolusDelivered = danaRPump.extendedBolusAmount
            result.isPercent = false
            aapsLogger.debug(LTag.PUMP, "setExtendedBolus: OK")
            return result
        }
        result.enacted = false
        result.success = false
        result.comment = resourceHelper.gs(R.string.danar_valuenotsetproperly)
        aapsLogger.error("setExtendedBolus: Failed to extended bolus")
        return result
    }

    @Synchronized
    override fun cancelTempBasal(force: Boolean): PumpEnactResult {
        val result = PumpEnactResult(injector)
        val runningTB = treatmentsPlugin.getTempBasalFromHistory(System.currentTimeMillis())
        if (runningTB != null) {
            danaRSService?.tempBasalStop()
            result.enacted = true
            result.isTempCancel = true
        }
        return if (!danaRPump.isTempBasalInProgress) {
            result.success = true
            result.isTempCancel = true
            result.comment = resourceHelper.gs(R.string.virtualpump_resultok)
            aapsLogger.debug(LTag.PUMP, "cancelRealTempBasal: OK")
            result
        } else {
            result.success = false
            result.comment = resourceHelper.gs(R.string.danar_valuenotsetproperly)
            result.isTempCancel = true
            aapsLogger.error("cancelRealTempBasal: Failed to cancel temp basal")
            result
        }
    }

    @Synchronized override fun cancelExtendedBolus(): PumpEnactResult {
        val result = PumpEnactResult(injector)
        val runningEB = treatmentsPlugin.getExtendedBolusFromHistory(System.currentTimeMillis())
        if (runningEB != null) {
            danaRSService?.extendedBolusStop()
            result.enacted = true
            result.isTempCancel = true
        }
        return if (!danaRPump.isExtendedInProgress) {
            result.success = true
            result.comment = resourceHelper.gs(R.string.virtualpump_resultok)
            aapsLogger.debug(LTag.PUMP, "cancelExtendedBolus: OK")
            result
        } else {
            result.success = false
            result.comment = resourceHelper.gs(R.string.danar_valuenotsetproperly)
            aapsLogger.error("cancelExtendedBolus: Failed to cancel extended bolus")
            result
        }
    }

    override fun getJSONStatus(profile: Profile, profileName: String): JSONObject {
        val now = System.currentTimeMillis()
        if (danaRPump.lastConnection + 5 * 60 * 1000L < System.currentTimeMillis()) {
            return JSONObject()
        }
        val pumpJson = JSONObject()
        val battery = JSONObject()
        val status = JSONObject()
        val extended = JSONObject()
        try {
            battery.put("percent", danaRPump.batteryRemaining)
            status.put("status", if (danaRPump.pumpSuspended) "suspended" else "normal")
            status.put("timestamp", DateUtil.toISOString(danaRPump.lastConnection))
            extended.put("Version", BuildConfig.VERSION_NAME + "-" + BuildConfig.BUILDVERSION)
            if (danaRPump.lastBolusTime != 0L) {
                extended.put("LastBolus", dateUtil.dateAndTimeString(danaRPump.lastBolusTime))
                extended.put("LastBolusAmount", danaRPump.lastBolusAmount)
            }
            val tb = treatmentsPlugin.getTempBasalFromHistory(now)
            if (tb != null) {
                extended.put("TempBasalAbsoluteRate", tb.tempBasalConvertedToAbsolute(now, profile))
                extended.put("TempBasalStart", dateUtil.dateAndTimeString(tb.date))
                extended.put("TempBasalRemaining", tb.plannedRemainingMinutes)
            }
            val eb = treatmentsPlugin.getExtendedBolusFromHistory(now)
            if (eb != null) {
                extended.put("ExtendedBolusAbsoluteRate", eb.absoluteRate())
                extended.put("ExtendedBolusStart", dateUtil.dateAndTimeString(eb.date))
                extended.put("ExtendedBolusRemaining", eb.plannedRemainingMinutes)
            }
            extended.put("BaseBasalRate", baseBasalRate)
            try {
                extended.put("ActiveProfile", profileFunction.getProfileName())
            } catch (e: Exception) {
                aapsLogger.error("Unhandled exception", e)
            }
            pumpJson.put("battery", battery)
            pumpJson.put("status", status)
            pumpJson.put("extended", extended)
            pumpJson.put("reservoir", danaRPump.reservoirRemainingUnits.toInt())
            pumpJson.put("clock", DateUtil.toISOString(now))
        } catch (e: JSONException) {
            aapsLogger.error("Unhandled exception", e)
        }
        return pumpJson
    }

    override fun manufacturer(): ManufacturerType {
        return ManufacturerType.Sooil
    }

    override fun model(): PumpType {
        return PumpType.DanaRS
    }

    override fun serialNumber(): String {
        return danaRPump.serialNumber
    }

    override fun getPumpDescription(): PumpDescription {
        return pumpDesc
    }

    override fun shortStatus(veryShort: Boolean): String {
        var ret = ""
        if (danaRPump.lastConnection != 0L) {
            val agoMillis = System.currentTimeMillis() - danaRPump.lastConnection
            val agoMin = (agoMillis / 60.0 / 1000.0).toInt()
            ret += "LastConn: $agoMin minago\n"
        }
        if (danaRPump.lastBolusTime != 0L)
            ret += "LastBolus: ${DecimalFormatter.to2Decimal(danaRPump.lastBolusAmount)}U @${DateFormat.format("HH:mm", danaRPump.lastBolusTime)}"

        val activeTemp = treatmentsPlugin.getRealTempBasalFromHistory(System.currentTimeMillis())
        if (activeTemp != null)
            ret += "Temp: ${activeTemp.toStringFull()}"

        val activeExtendedBolus = treatmentsPlugin.getExtendedBolusFromHistory(System.currentTimeMillis())
        if (activeExtendedBolus != null)
            ret += "Extended: $activeExtendedBolus\n"

        if (!veryShort) {
            ret += "TDD: ${DecimalFormatter.to0Decimal(danaRPump.dailyTotalUnits)} / ${danaRPump.maxDailyTotalUnits} U"
        }
        ret += "Reserv: ${DecimalFormatter.to0Decimal(danaRPump.reservoirRemainingUnits)} U"
        ret += "Batt: ${danaRPump.batteryRemaining}"
        return ret
    }

    override fun isFakingTempsByExtendedBoluses(): Boolean = false
    override fun loadTDDs(): PumpEnactResult = loadHistory(RecordTypes.RECORD_TYPE_DAILY)
    override fun getCustomActions(): List<CustomAction>? = null
    override fun executeCustomAction(customActionType: CustomActionType) {}
    override fun canHandleDST(): Boolean = false
    override fun timezoneOrDSTChanged(timeChangeType: TimeChangeType?) {}
}