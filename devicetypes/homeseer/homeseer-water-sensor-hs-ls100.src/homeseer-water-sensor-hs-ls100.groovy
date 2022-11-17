/**
 *  HS-LS100+
 *
 *  Originally Copyright 2018 HomeSeer, based on kf code
 *  https://homeseer.com/support/HS200-ST/HS-LS100_DeviceHandler.txt
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 * Version 1.0 1/15/18
 *
 * 
 * Version 1.5 - 5/30/2021
 *    - Added support to pair with either ZWAVE_LEGACY_NON_SECURE or ZWAVE_S2_UNAUTHENTICATED.
 *    - Added preferences for setting the wake up/battery report interval.
 *    - Added preferences and support for P17-Leak Report Interval.
 *    - Added preferences and support for P18-Enable Shock Sensor.
 *    - Fixed preferences and support for P19-Temperature Report Interval.
 *    - Fixed preferences and support for P20-High Temperature Trigger Value.
 *    - Fixed preferences and support for P22-Low Temperature Trigger Value.
 *    - Added preferences and support for P24-Enable blinking LED when Alarm Trigger
 *    - Added attributes for last checkin, last battery report, last wet alarm.
 *    - Added attributes and logic to query the firmware version from the device.
 *    - Set HealthCheck timing to be based on check in interval or (if enabled) P19-Temperature Report Interval, whichever is more frequent.
 *    - Refactored logic to only request a Battery Report when one hasn't been received in the expected time.
 *    - Fingerprint match required deviceJoinName element to be more specific than the default "Z-Wave Water Sensor" handler.  Otherwise,
 *      the custom DTH was not being used for new devices by default.
 *   
 * TODO:
 * Add C/F/default configuration handling for temperature updates
 *   
 * As of 2021, for version 1.04 firmware.
 * Notes about this device:
 * WAKE UP INTERVAL / BATTERY SENSOR OPERATION
 * The wake up interval drives the frequency of battery level reports.   Upon waking up, the unit will send a battery report and
 * a wake up report (the order received is non deterministic).  
 * During the wake up time we can send the device new configuration or updated parameters.
 * The wake up interval set on the device is in increments of 3600 seconds (1 hour).  Our configuration therefore sets the 
 * interval in hours (0h - 744 hours).  
 * NOTE: If you set this value to 0, the device will no longer send battery reports, but it also won't ever wake up to check in.
 *       This will keep the device from getting any configuration updates.   You'll need to manually wake it up by removing 
 *       and reinserting the battery if you want to make any changes to it.
 *       This seems like a bad idea, but the device does support it.
 * 
 * Parameter 17 - Leak Report Interval:
 *   The device will not accept setting to 0.   Disabling Leak Reports makes no sense on a Leak Sensor.   Setting to 0 will simply
 *   set to the default of 5 minutes.
 *
 * Parameter 19 - Temperature report interval:
 *   Runs in addition to the regular wake ups, and only reports temperature changes.
 *   Can be set from 3-240, value is * 10 minutes (3 = 30 minutes, 6 = 1 hour, 12 = 2 hours, 144 = 24 hours, 240 = 2400 minutes).  
 *   Values 0-2 will disable the temperature reports.   Device default is 0.
 *   
 * Heath Check (ONLINE/OFFLINE STATUS)
 * The DTH will base its check in interval on either the wakeup/battery interval, or the P19 temperature report interval, if they are 
 * enabled, and whichever is lower.
 * The timeout is double the selected interval, plus 2 minutes.
 * For example, if the wake up interval is 2 hours (120 minutes) and the P19 temperature report interval is 
 * 3 hours (180 minutes), then the check in interval will be (120 + 120) + 2, or 4 hours and 2 minutes.
 *
 * Zwave certification: https://products.z-wavealliance.org/products/2735
 * 
 * Supports Z-Wave Association Tool
 *  See: https://community.inovelli.com/t/how-to-using-the-z-wave-association-tool-in-smartthings/1944 for info
 */
 
import groovy.transform.Field

@Field static Map commandClassVersions = [
    0x30: 2,  // Sensor Binary
    0x31: 5,  // Sensor Multilevel
    0x59: 1,  // AssociationGrpInfo
    0x5A: 1,  // DeviceResetLocally
    0x5E: 2,  // ZwaveplusInfo
    0x70: 1,  // Configuration
    0x71: 3,  // Notification (Device supports V5)
    0x72: 2,  // ManufacturerSpecific
    0x73: 1,  // Powerlevel
    0x7A: 2,  // Firmware Update Meta Data (Device supports V3)
    0x80: 1,  // Battery
    0x84: 2,  // WakeUp
    0x85: 2,  // Association
    0x86: 1,  // Version (Device supports V2)  
    0x98: 1,  // Security
    0x9F: 1	  // Security 2           
] 
 
metadata {
	definition (
		name: "HomeSeer Water Sensor HS-LS100+", 
		namespace: "HomeSeer", 
		author: "support@homeseer.com",
        mnmm: "SmartThings", vid: "generic-leak-3",      // Generic capability for Water Sensor, Battery, Temperature Measurement, Tamper Alert  
        ocfDeviceType: "x.com.st.d.sensor.moisture",     // set the icon to a leak sensor
        runLocally: false
	) {
    	capability "Water Sensor"  
        capability "Temperature Measurement"
        // capability "Motion Sensor"
        capability "Tamper Alert"               // Use instead of "Motion Sensor" for shock sensor alerts, as a Motion Sensor is in conflict with a Water Sensor.
		capability "Battery"
		capability "Configuration"
		capability "Refresh"       
		capability "Health Check"        
        capability "Sensor"
      
		
        // Attributes to save data outside of the capability of the device.
        // For any supported attribute, it is expected that the Device Handler creates and sends Events with the name of the attribute.
        // Current values are fetchable via device.currentValue(attributeName)
        // Date/time strings are in "YYYY-MM-DD HH:MM:SS" format
        attribute "wakeUpInterval", "string"                // Value of wake up interval, as configured on the device.  Reported in seconds.
        attribute "basicSetCommand", "string"               // Value of Parameter #14, as configured on the device.
        attribute "leakReportingInterval", "string"         // Value of Parameter #17, as configured on the device.
        attribute "tempReportingInterval", "string"         // Value of Parameter #19, as configured on the device.
		attribute "tempTriggerHighValue", "string"          // Value of Parameter #20, as configured on the device.  Currently reported in F.
        attribute "tempTriggerLowValue", "string"           // Value of Parameter #22, as configured on the device.  Currently reported in F.

	    attribute "lastWakeUpDate", "string"                // Date/time string of last wake up 
	    attribute "lastBatteryReportDate", "string"         // Date/time string of last battery report 
        attribute "lastTemperatureReportDate", "string"     // Date/time string of last temperature report
		attribute "lastCheckIn", "string"                   // Date/Time string of last data received
		attribute "lastWetDate", "string"                   // Date/Time string of last wet report received

		command "setAssociationGroup", ["number", "enum", "number", "number"] // group number, nodes, action (0 - remove, 1 - add), multi-channel endpoint (optional)

		// fingerprint mfr:"000C", prod:"0201", model:"000A", deviceJoinName: "HomeSeer LS100+ Water Leak Sensor"
	}
	
	simulator { 
    		status "battery report":
                       zwave.batteryV1.batteryReport(batteryLevel:0xFF).incomingMessage()
            status "dry": "command: 3003, payload: 00"
			status "wet": "command: 3003, payload: FF"
			status "dry notification": "command: 7105, payload: 00 00 00 FF 05 FE 00 00"
			status "wet notification": "command: 7105, payload: 00 FF 00 FF 05 02 00 00"
			status "wake up": "command: 8407, payload: "            
    }
	
    // Values are fetchable directly by referring to the input name.
	preferences {
        input (
            title: 'Z Wave Configuration and Parameters',
            description: 'Configurations available on the device',
            type: "paragraph",
            element: "paragraph"
        )
        
        // Wake Up / Battery Reporting Interval.   Specified in hours, as the device requires increments of 3600 seconds.  0 will disable wake up / battery reports.        
        //input name: "wakeUpInterval", type: "number", title: "Wake Up / Battery Report Interval", 
        //     description: "Number of hours between wake up/battery reports.  Note: 0 will disable both wake up and battery reports - use with caution.  Default: 1 (1 hour)", 
        //      range: "0..744", displayDuringSetup: false
        
        input (
            title: "Wake Up / Battery Report Interval",
            description: "Number of hours between wake up/battery reports.\nNote: 0 will disable both wake up and battery reports - use with caution.\nDefault: 1 (1 hour)",
            type: "paragraph",
            element: "paragraph"
        )
        input(
            name: "wakeUpInterval",
            type: "number",
            title: "Wake Up / Battery Report Interval Set value (range 0..744)",
            range: "0..744",
            required: false
        )
       
		// 14: Enable / Disable Basic Set Command        
		input name: "basicSetCommand", type: "bool", title: "Enable Basic Set Command", description: "Enable Basic Set Command for reporting leak in addition to Notification Report.  Disabled by default.", required: false, displayDuringSetup: false
        // 17: Leak Report Interval
        input name: "leakReportInterval", type: "number", title: "Leak Report Interval:", description: "Frequency of leak alarms once detected.  Not set / default (5 minutes), 1-255 = minutes between leak alarms.", required: false, range: "1..255", displayDuringSetup: false
        // 18: Enable Shock Sensor (0 = disable, 1 = enable.  Default = 0)
        input name: "shockSensor", type: "bool", title: "Enable Shock Sensor:", description: "Enable Shock Sensor reporting.  Disabled by default to conserve battery life.", required: false
        // 19: Temperature Report Interval (0 = disable, 3 to 240 (in minutes).  Default: 10)
		// input "tempReporting", "number", title: "Temperature Reporting Interval:", defaultValue: tempReportingSetting, required: false, displayDuringSetup: false, options: tempReportingOptions.collect { it.name }
        input "tempReportingInterval", "number", title: "Temperature Report Interval:", description: "(multiply by 10 for minutes: 3 = 30 min) 0 = disable.  3-240 valid range.", required: false, range: "0..240", displayDuringSetup: false
        // TODO 20: Set High Temperature Trigger value (-67 to 257 F), default 104 (104F).
        input "tempTriggerHighValue", "number", title: "High Temperature Trigger Value:", description: "-67 to 257F, default = 104F", required: false,  displayDuringSetup: false
        // TODO 22: Set Low Temperature Trigger value (-67 to 257 F), default 32 (32F).  
        input "tempTriggerLowValue", "number", title: "Low Temperature Trigger Value:", description: "-67 to 257F, default = 32F", required: false,  displayDuringSetup: false
        // 24: Enable blinking LED when Alarm Trigger
        input name: "blinkLEDAlarm", type: "bool", title: "Enable Blinking LED When Alarm Triggers:", required: false, defaultValue: true
        // TODO 32: Set Value for Low Battery
        
        input name: "debugEnable", type: "bool", title: "Enable Debug Logging", required: true, defaultValue: false
        input name: "infoEnable", type: "bool", title: "Enable Informational Logging", required: true, defaultValue: true
	}

	tiles {
		multiAttributeTile(name:"water", type: "generic", width: 2, height: 2, canChangeIcon: false){
			tileAttribute ("device.water", key: "PRIMARY_CONTROL") {
				attributeState "dry", label:'Dry', icon: "st.alarm.water.dry", backgroundColor:"#ffffff"
				attributeState "wet", label:'Wet', icon: "st.alarm.water.wet", backgroundColor:"#00a0dc"
			}
		}			
		
		valueTile("temperature", "device.temperature", inactiveLabel: false, width: 2, height: 2) {
			state "temperature", label:'${currentValue}B0',
				backgroundColors:[
					[value: 31, color: "#153591"],
					[value: 44, color: "#1e9cbb"],
					[value: 59, color: "#90d2a7"],
					[value: 74, color: "#44b621"],
					[value: 84, color: "#f1d801"],
					[value: 95, color: "#d04e00"],
					[value: 96, color: "#bc2323"]
				]
		}	       
       
        valueTile("tamper", "device.tamper", height: 2, width: 2, decoration: "flat") {
			state "clear", label: 'tamper clear', backgroundColor: "#ffffff"
			state "detected", label: 'tampered', backgroundColor: "#ff0000"
		}
       
        //valueTile("motion", "device.motion", decoration: "flat", inactiveLabel: false, width: 2, height: 2) {
		//	state("active", label: 'motion', icon: "st.motion.motion.active", backgroundColor: "#00A0DC")
		//	state("inactive", label: 'no motion', icon: "st.motion.motion.inactive", backgroundColor: "#CCCCCC")
		//}
        
		valueTile("battery", "device.battery", decoration: "flat", inactiveLabel: false, width: 2, height: 2){
			state "battery", label: '${currentValue}% battery', unit:""
		}
        
		standardTile("refresh", "device.refresh", inactiveLabel: false, decoration: "flat", width: 2, height: 2) {
			state "default", action: "refresh.refresh", icon: "st.secondary.refresh"
		}
					
		main "water"
		details(["water", "temperature", "tamper", "battery", "refresh"])
	}
}

// State variables and what they mean:
// state.pendingChanges
// state.isConfigured
// state.pendingRefresh

// installed() runs just after a sensor is paired using the "Add a Thing" method in the SmartThings mobile app
def installed() {
	logTrace "installed()"
}
 
// Sets flag so that configuration is updated the next time it wakes up.
def updated() {	
	// This method always gets called twice when preferences are saved.
	if (!isDuplicateCommand(state.lastUpdated, 3000)) {		
		state.lastUpdated = new Date().time
		logTrace "updated()"

		logForceWakeupMessage "The configuration will be updated the next time the device wakes up."
		// state.pendingChanges = true
	}		
}

// If you add the Configuration capability to your device type, this command will be called right after the device joins to set
// device-specific configuration commands.
// configure() runs after installed() when a sensor is paired
// Initializes the device state when paired and updates the device's configuration.
def configure() {
	def cmds = []

    def refreshAll = (!state.isConfigured || state.pendingRefresh)
    
	logTrace "configure() refreshAll: ${refreshAll}, state.isConfigured = ${state.isConfigured}, state.pendingRefresh ${state.pendingRefresh}"

	if (!state.isConfigured) {
		//logTrace "Waiting 1 second because this is the first time being configured"
        //cmds << "delay 1000"
        
        // Send the first events the tiles need to initialize
		sendEvent(getEventMap("water", "dry", false))  // For water leak sensor 
        //sendEvent(getEventMap("motion", "inactive", false))  // For motion sensor
        sendEvent(getEventMap("tamper", "clear", false)) // For tamper alert 
	}
       
    // Update the wake up interval if needed.
    cmds += updateWakeUpInterval()
	
    // use getConfigData(), for each parameter, check if we need to send a configuration command.
    // Any that pass will be sent, followed by a "get current value" request for the same parameter.
	configData.sort { it.paramNum }.each {
    	def val = it.value
        if ( val == null ) {
        	val = it?.default
        }
                
		cmds += updateConfigVal(it.paramNum, it.size, val, refreshAll)	
        
        // A bit of a hack here
        if (it.paramNum == 19) {
        	// If this is the temperature reporting interval, see if the value is 0 and we had previous reports
            if (val == 0 && state?.lastTemperatureReport > 0) {
                logDebug("Clearing temperature attributes due to temperature reporting disabled (lastTemperatureReport:${state.lastTemperatureReport})")
            	sendEvent(name: "temperature", value: null)
                state.remove("lastTemperatureReport")
            }
        }
	}
	
    // state.pendingChanges = false
    	
    // Do we want to request a battery update?
	if (refreshAll || canReportBattery()) {
    	logTrace "Requesting battery status"
		cmds << batteryGetCmd()
	}
    
    if (refreshAll || !state["firmwareVersion"]){
    	logTrace "Requesting firmware version"
        cmds << versionGetCmd()
        cmds << firmwareMdGetCmd()
    }
    
    if (refreshAll || !state["MSR"]) {
    	logTrace "Requesting MSR"
    	cmds << manufacturerSpecficGetCmd()
    }

    if (state.lifelineAssoc != true) {
		logTrace "Setting lifeline Association"
		cmds << lifelineAssociationSetCmd()
		cmds << lifelineAssociationGetCmd()
	}
    
    if (!state.associationGroups) { 
    	logTrace "Requesting supported association groups from device"
    	cmds << associationGroupingsGetCmd()
    	cmds << associationGetCmd(1)
    	cmds << associationGetCmd(2)
    }
    	       
	if (cmds) {
		logDebug "configure() Sending configuration to device with 500ms delay between: ${cmds}"
		return delayBetween(cmds, 500)
	} else {
		return []
	}	
}

private updateConfigVal(paramNum, paramSize, val, refreshAll) {
	def result = []
    // Get the last reported value from the device.
	def configVal = state["configVal${paramNum}"]
    
    // If we're force refreshing or we don't yet have the value, then send command to get the parameter value.
    def requestGet = (refreshAll || configVal == null)
	
    // Is the new value non null?
    // Are we different (or forced?)
	if (refreshAll || (configVal != val)) {
    	if (val != null) {
            // Send a command to update the device with the new configuration value.
        	logTrace "updateConfigVal() updating param ${paramNum} from ${configVal} to ${val}"
			result << configSetCmd(paramNum, paramSize, val)
            requestGet = true
        } else {
        	// The "value" we'd be trying to set is null (ie, default), so instead just tell the device to use its default.
            logTrace "updateConfigVal() reverting param ${paramNum} to device default."
            result << configSetDefaultCmd(paramNum, paramSize)
            requestGet = true
        }
	}

    if (requestGet) {
    	logTrace "updateConfigVal() requesting param ${paramNum}, current value ${configVal}"
    	result << configGetCmd(paramNum)
    }
	return result
}

private updateWakeUpInterval() {
	def cmds = []

	// Our device allows wake up intervals between 0 and 2678400 seconds (0-744 hours), in increments of 3600 seconds (1 hour).
    // If we have a wake up interval setting (in hours, as the device only accepts increments of 3600)
    // AND our wake up interval we have is different than the setting
    def settingWakeup = (state?.wakeUpInterval == null)
    
    // Note: 
    // state.wakeUpInterval is in seconds, or null if not yet reported from device.
    // wakeUpIntervalSetting is in hours, or null if not set.
    def wakeInterval = wakeUpIntervalSetting  // in hours, or null if not set
    logTrace "state.wakeUpInterval = ${state?.wakeUpInterval}  setting = ${wakeInterval}"
    if (wakeInterval != null) {
        def interval = wakeInterval * 60 * 60  // convert to seconds
    	if ( state?.wakeUpInterval != interval ) {
        	// Set the new value, converting to seconds
        	logTrace "Requesting a new wake up interval of ${wakeInterval}h."
        	cmds << wakeUpIntervalSetCmd(interval)
        	settingWakeup = true
        }
    } 
      
    // If we don't yet have the wakeup interval from the device, or we just requested it to change, then 
    // request a wake up interval report.
    if (settingWakeup) {
        logTrace "Requesting a wake up interval report."
    	cmds << wakeUpIntervalGetCmd()
    }
    
    return cmds
}

// Set the Health Check interval so that it can be skipped once plus 2 minutes.
// We base the interval on either the check in interval or (if enabled) the temperature interval, which ever is lowest.
// When either is set, we recalc and send an event notifying Health Check.
private updateHealthCheckInterval() {
    // Use the device's wake up interval (in seconds) as the starting point.  If not set for some reason use 12 hours.
    def checkInterval = state.wakeUpInterval ?: (12 * 60 * 60)   
    
    def tempInterval = tempReportingIntervalSetting  // get value, or null    
    // If the temperature interval is set and not disabled, and its less than the check in interval, use that.
    if (tempInterval && tempInterval >= 3) // in a valid range (3-240)
    {
    	// Convert tempInterval (in 10's of minutes, 3 = 30 minutes) to seconds
        tempInterval = (tempInterval * 10) * 60
        
        // Is the temperature interval less than the check in interval?
        if (tempInterval < checkInterval) {
        	checkInterval = tempInterval
        }   
    }
    
    // checkInterval is in seconds.
    if (checkInterval > 0) {
    	// Allow for it to be skipped once, plus 2 minutes.
    	checkInterval = (checkInterval * 2) + (2 * 60)
    }
    logTrace "Updating checkInterval to ${checkInterval} seconds"
	
	sendEvent(name: "checkInterval", value: checkInterval, displayed: false, data: [protocol: "zwave", hubHardwareId: device.hub.hardwareID])
}

// Required for HealthCheck Capability, but doesn't actually do anything because this device sleeps.
def ping() {
	logDebug "ping()"	
}

// Forces the configuration to be resent to the device the next time it wakes up.
def refresh() {	
	logForceWakeupMessage "The configuration and sensor data will be refreshed the next time the device wakes up."
	state.pendingRefresh = true
}

private logForceWakeupMessage(msg) {
	logDebug "${msg}  You can force the device to wake up immediately by removing and re-inserting the battery."
}

// Processes messages received from device.
def parse(String description) {
	def result = []
 
	try {
		def cmd = zwave.parse(description, commandClassVersions)
		if (cmd) {
			result += zwaveEvent(cmd)
		}
		else {
			logDebug "Unable to parse description: $description"
		}

		sendLastCheckInEvent()
	}
	catch (e) {
		log.error "$e"
	}

    logTrace "parse() processed '$description' to $result"  

	return result
}

private sendLastCheckInEvent() {
	if (!isDuplicateCommand(state.lastCheckIn, 60000)) {
		state.lastCheckIn = new Date().time

		// Set the attribute to a human readable date/time of now.
		sendEvent(name: "lastCheckIn", value: convertToLocalTimeString(new Date()), displayed: false)
	}
}

def zwaveEvent(physicalgraph.zwave.commands.securityv1.SecurityMessageEncapsulation cmd) {
	def encapCmd = cmd.encapsulatedCommand(commandClassVersions)

	def result = []
	if (encapCmd) {
		result += zwaveEvent(encapCmd)
	}
	else {
		log.warn "Unable to extract encapsulated cmd from $cmd"
	}
	return result
}

// WAKE UP (8407) Event handler.
// Updates devices configuration, requests battery report, and/or creates last checkin event.
//
// Battery powered devices can be configured to periodically wake up and
// check in. They send this command and stay awake long enough to receive
// commands, or until they get a WakeUpNoMoreInformation command that
// instructs them that there are no more commands to receive and they can
// stop listening.
def zwaveEvent(physicalgraph.zwave.commands.wakeupv2.WakeUpNotification cmd)
{
	logTrace "onWakeUp() ${cmd}"
	def cmds = []
    
    // Filter out duplicate wake up commands received
    if (!isDuplicateCommand(state.lastWakeUp, 10000)) {
		state.lastWakeUp = now()

		// Check to see if there is anything to send while the device is listening.
        // Check first for configuration updates.  These are a bit expensive (set, then get for each) so 
        // we do those first.
        // If none, see if we should request a battery status update.  If we've not gotten
        // one for a while, or we're in a forced refresh, request one.
        
        // We may have configuration changes to send or things to sync.
        cmds += configure()
        
        // TO DEBUG WAKE UP CAPABILITIES, UNCOMMENT THESE LINES 
        // logTrace "onWakeUp() requesting interval capabilities/settings"
        // Ask for your version capabilities

        // cmds << "delay 500"
        // cmds << wakeUpIntervalCapabilitiesGetCmd()
	}
	
    // Tell the device we're done after 2 more seconds.
	if (cmds) {
		cmds << "delay 2000"
	}
    
    // Append the WakeUpNoMoreInformation command, we're done.
	cmds << wakeUpNoMoreInfoCmd()
    
    // Timestamp the last time we woke up.
    sendEvent(name: "lastWakeUpDate", value: convertToLocalTimeString(new Date()), displayed: false, isStateChange: true)
    
    // Compatibility
    sendEvent(name: "tamper", value: "clear")

    return response(cmds)
}

// Handle a wake up interval capabilities report
def zwaveEvent(physicalgraph.zwave.commands.wakeupv2.WakeUpIntervalCapabilitiesReport cmd) {
	logTrace "WakeUpIntervalCapabilitiesReport: $cmd"
    
    return []
}

def zwaveEvent(physicalgraph.zwave.commands.wakeupv2.WakeUpIntervalReport cmd) {
    state.lastWakeUpIntervalReport = new Date().time  // timestamp
    state.wakeUpInterval = cmd.seconds
    
    def intervalStr = String.format("%d s", state.wakeUpInterval)
    
    logDebug "WakeupIntervalReport: ${intervalStr} from $cmd"
    // Set the "wakeUpInterval" attribute value.
	sendEventIfNew("wakeUpInterval", intervalStr, false)
    
    // Update HealthCheck with the latest 
    updateHealthCheckInterval()
    
    return []
}

private sendResponse(cmds) {
	logTrace "sendResponse() $cmds"
	def actions = []
	cmds?.each { cmd ->
		actions << new physicalgraph.device.HubAction(cmd)
	}	
	sendHubCommand(actions)
	return []
}

// Battery Report handler
def zwaveEvent(physicalgraph.zwave.commands.batteryv1.BatteryReport cmd) {
	logTrace "BatteryReport: $cmd"
	def val = (cmd.batteryLevel == 0xFF ? 1 : cmd.batteryLevel)
	if (val > 100) {
		val = 100
	}
    def now_ts = new Date().time
    
    if ( state.lastBatteryReport ) {
   		// If we've received a battery report in the past, log some extra stuff.
    	int minutes_since = (now_ts - state.lastBatteryReport) / 1000 / 60  // convert from ms to minutes
    	def last_date = device.currentValue("lastBatteryReportDate")
    
    	logDebug "BatteryReport: Last Battery Report ${minutes_since} minutes ago on ${last_date}. Now ${val}%."
    }
    
    // Update the world
	state.lastBatteryReport = now_ts   // update the last timestamp of a battery report 
    
    sendEvent(name: "lastBatteryReportDate", value: convertToLocalTimeString(new Date()), displayed: false, isStateChange: true)
    // def event = createEvent(createEventMap("battery", val, null, null, "%"))
    def event = createEvent(name: "battery", value: val, displayed: true, unit: "%")
    logInfo(event.descriptionText)
    return event
}	

// Version Report handler
def zwaveEvent(physicalgraph.zwave.commands.versionv1.VersionReport cmd) {
	logTrace "VersionReport: ${cmd}"
	def subVersion = String.format("%02d", cmd.applicationSubVersion)
	def fullVersion = "${cmd.applicationVersion}.${subVersion}"
    
    updateDataValue("firmwareVersion", "${fullVersion}")
    updateDataValue("zWaveProtocolVersion", "${cmd.zWaveProtocolVersion}.${cmd.zWaveProtocolSubVersion}")
    updateDataValue("zWaveLibraryType", "${cmd.zWaveLibraryType}")   
	
	logDebug "Firmware: ${fullVersion}"

	return []
}

// MSR handler
def zwaveEvent(physicalgraph.zwave.commands.manufacturerspecificv2.ManufacturerSpecificReport cmd) {
	logTrace "ManufacturerSpecificReport: ${cmd}"
    updateDataValue("MSR", String.format("%04X-%04X-%04X", cmd.manufacturerId, cmd.productTypeId, cmd.productId))
    if (cmd.manufacturerName)
    	updateDataValue("manufacturer", cmd.manufacturerName)
}

// Firmware MD report handler
def zwaveEvent(physicalgraph.zwave.commands.firmwareupdatemdv2.FirmwareMdReport cmd) {
	logTrace "FirmwareMdReport: ${cmd}"
    updateDataValue("firmware", "${cmd.manufacturerId}-${cmd.firmwareId}-${cmd.checksum}")
}

// Configuration Report handler
// Handles updates from Parameters
def zwaveEvent(physicalgraph.zwave.commands.configurationv1.ConfigurationReport cmd) {
    logTrace "ConfigurationReport: ${cmd}"
    def needHealthCheckUpdate = false
    
	def param = configData.find { it.paramNum == cmd.parameterNumber }
    def name = param?.name
	if (name) {	
		def val = hexToInt(cmd.configurationValue, cmd.size)
	
		logDebug "Setting #${cmd.parameterNumber} ${name} = ${val}"
	
    	// Stores the parameter values so that it only updates them when they've changed or a refresh was requested.
		state."configVal${cmd.parameterNumber}" = val
        
        needHealthCheckUpdate = param?.checkInterval
        
        // Send optional event for attribute if set and changed
        if (param?.attribute) {
            if (param?.attrDesc == null) { 
            	sendEventIfNew("${param.attribute}", val, false)
            } else {
                // If applicable, generate a formatted value for human readable strings
                def func = param?.attrDesc
                def formattedVal = func(val)
                sendEventIfNew("${param.attribute}", formattedVal, false)
            }
        }    
	}
	else {
		logDebug "Parameter ${cmd.parameterNumber}: ${cmd.configurationValue}"
	}
	state.isConfigured = true
	state.pendingRefresh = false
    
    // If a dependent parameter changed, Update Health Check
    if (needHealthCheckUpdate) {
    	updateHealthCheckInterval()
    }
	return []
}

def sensorValueEvent(value) {
	def eventValue = value ? "wet" : "dry"
	createEvent(name: "water", value: eventValue, descriptionText: "$device.displayName is $eventValue")
}

def motionEvent(value) {
	def map = [name: "motion"]
	if (value) {
		map.value = "active"
		map.descriptionText = "$device.displayName detected motion"
	} else {
		map.value = "inactive"
		map.descriptionText = "$device.displayName motion has stopped"
	}
	createEvent(map)
}

// Creates notification reports.
// Handles WATER and SHOCK (motion) reports.
def zwaveEvent(physicalgraph.zwave.commands.notificationv3.NotificationReport cmd) {
	def result = []	
	logTrace "NotificationReport: $cmd"
	
	if (cmd.notificationType == 0x05) { // NOTIFICATION_TYPE_WATER
		switch (cmd.event) {
			case 0x00:  // STATE_IDLE
				logDebug "Sensor is Dry"				
				// result << createEvent(getEventMap("water", "dry"))
                // TODO: check what event parameters the LS100+ is using and for what
				if (cmd.eventParametersLength && cmd.eventParameter.size() && cmd.eventParameter[0] > 0x02) {                   
					result << createEvent(descriptionText: "Water alarm cleared", isStateChange: true)
				} else {
					result << createEvent(name: "water", value: "dry")
				}
				break
            case 0xFE:  // UNKNOWN_EVENT_STATE
                result << createEvent(name: "water", value: "dry")
                break
            case 0x01:  // LEAK_DETECTED_LOCATION_PROVIDED
			case 0x02:  // LEAK_DETECTED
                logDebug "Sensor is Wet" 
				result << createEvent(name: "water", value: "wet")
                sendEventIfNew("lastWetDate", convertToLocalTimeString(new Date()), false)
				break
			default:
				logDebug "Sensor is ${cmd.event}"
		}
	} else if (cmd.notificationType == 0x04) { // NOTIFICATION_TYPE_HEAT 
		if (cmd.event <= 0x02) {
			result << createEvent(descriptionText: "$device.displayName detected overheat", isStateChange: true)
		} else if (cmd.event <= 0x04) {
			result << createEvent(descriptionText: "$device.displayName detected rapid temperature rise", isStateChange: true)
		} else {
			result << createEvent(descriptionText: "$device.displayName detected low temperature", isStateChange: true)
		}
	} else if (cmd.notificationType == 0x07) {   // Shock Sensor Notification NOTIFICATION_TYPE_BURGLAR
        switch (cmd.event) {
                case 0:
                    result << motionEvent(0)
                    result << createEvent(name: "tamper", value: "clear")
                    break
                case 3:
                case 9:
                    result << createEvent(name: "tamper", value: "detected", descriptionText: "$device.displayName was tampered")
                    // Clear the tamper alert after 10s. This is a temporary fix for the tamper attribute until local execution handles it
                    //unschedule(clearTamper, [forceForLocallyExecuting: true])
                    //runIn(10, clearTamper, [forceForLocallyExecuting: true])
                    break
                case 7:
                case 8:
                    result << motionEvent(1)
                    break
            }
    }
    else {
    	logTrace "Unhandled notification type ${cmd.notificationType}"
    }
    
	return result
}

// Handle temperature sensor reports
def zwaveEvent(physicalgraph.zwave.commands.sensormultilevelv5.SensorMultilevelReport cmd) {
	logTrace "SensorMultilevelReport: ${cmd}"
		
	def result = []
	if (cmd.sensorType == 1) {  // SENSOR_TYPE_TEMPERATURE_VERSION_1
		result += handleTemperatureEvent(cmd)
	} else {
		logDebug "Unknown Sensor Type: ${cmd}"
	} 
	return result
}

private handleTemperatureEvent(cmd) {
	def result = []
	def cmdScale = cmd.scale == 1 ? "F" : "C"
	
	def val = convertTemperatureIfNeeded(cmd.scaledSensorValue, cmdScale, cmd.precision)	
    
	if ("$val".endsWith(".")) {
		val = safeToInt("${val}"[0..-2])
	}
   
    // Timestamp the event
    state.lastTemperatureReport = new Date().time
    // Set the attribute to a human readable date/time of now.
    sendEvent(name: "lastTemperatureReportDate", value: convertToLocalTimeString(new Date()), displayed: false, isStateChange: true)
	
    // CSS:
    // result << createEvent(createEventMap("temperature", val, null, "Temperature ${val}B0${getTemperatureScale()}", getTemperatureScale()))
	result << createEvent(createEventMap("temperature", val, true, "Temperature ${val}B0${getTemperatureScale()}", getTemperatureScale()))
	return result
}

// Create an event map for attribute values.
//    displayed defaults to if the value is the same as the last value, its hidden.  Override with passing in a value.
//    isStateChange is *ALWAYS* set.
// Should be used for variable values (temperature, battery level, etc).
private createEventMap(name, value, displayed=null, desc=null, unit=null) {	
	def eventMap = [
		name: name,
		value: value,
		displayed: (displayed == null ? ("${getAttrVal(name)}" != "${value}") : displayed),
		isStateChange: true
	]
	if (unit) {
		eventMap.unit = unit
	}
	if (desc && eventMap.displayed) {
		eventMap.descriptionText = "${device.displayName} - ${desc}"
        logInfo(eventMap.descriptionText)
	}
	else {
		logTrace "Creating Event: ${eventMap}"
	}
	return eventMap
}

private getAttrVal(attrName) {
	try {
		return device?.currentValue("${attrName}")
	}
	catch (ex) {
		logTrace "$ex"
		return null
	}
}
/////
/////  Association 
/////

def setAssociationGroup(group, nodes, action, endpoint = null){
    log.debug "group: ${group} , nodes: ${nodes}, action: ${action}, endpoint: ${endpoint}"
    def name = "Association${group}"
    switch (action) {
        case 0:
        state."del${name}" = nodes
        break
        case 1:
        state."add${name}" = nodes
        break
    }
}

void zwaveEvent(physicalgraph.zwave.commands.associationv2.AssociationReport cmd) {
	logTrace "AssociationReport $cmd"
	
	if (cmd.groupingIdentifier == 1) {
		logDebug "Lifeline Association: ${cmd.nodeId}"
		state.lifelineAssoc = (cmd.nodeId == [zwaveHubNodeId]) ? true : false
	}
    
    def temp = []
    if (cmd.nodeId != []) {
       cmd.nodeId.each {
          temp += it.toString().format( '%02x', it.toInteger() ).toUpperCase()
       }
    } 
    
    state."actualAssociation${cmd.groupingIdentifier}" = temp
    logDebug "${device.label?device.label:device.name}: Associations for Group ${cmd.groupingIdentifier}: ${temp}"
    updateDataValue("associationGroup${cmd.groupingIdentifier}", "$temp")
}

def zwaveEvent(physicalgraph.zwave.commands.associationv2.AssociationGroupingsReport cmd) {
    logTrace "AssociationGroupingsReport ${cmd}"
    sendEvent(name: "groups", value: cmd.supportedGroupings)
    logDebug "Supported association groups: ${cmd.supportedGroupings}"
    state.associationGroups = cmd.supportedGroupings
}


// Ignoring Sensor Binary Report (0x30) because water, temperature alarms and shock events are all 
// being handled by Notification Report.
def zwaveEvent(physicalgraph.zwave.commands.sensorbinaryv2.SensorBinaryReport cmd) {
	logTrace "SensorBinaryReport: $cmd"
	return []
}

def zwaveEvent(physicalgraph.zwave.commands.basicv1.BasicReport cmd) {
	logTrace "BasicReport: $cmd"
	return []
}

// Logs unexpected events from the device.
def zwaveEvent(physicalgraph.zwave.Command cmd) {
	log.warn("Unhandled Command: $cmd")
	return []
}

private sendEventIfNew(name, value, displayed) {
	if (device.currentValue("${name}") != value) {
		sendEvent(getEventMap(name, value, displayed))
	}
}

// Create an event map for any attribute, much more simple version than above.
// isStateChange=true if current attribute value differs.
// displayed = passed overrides.   If null, displayed = isStageChange.
// Only sets description if passed, only sets units if passed.
// Should be used for state values (open/close/wet/dry).
private getEventMap(name, value, displayed=null, desc=null, unit=null) {	
	def isStateChange = (device.currentValue(name) != value)
	displayed = (displayed == null ? isStateChange : displayed)
	def eventMap = [
		name: name,
		value: value,
		displayed: displayed,
		isStateChange: isStateChange
	]
	if (desc) {
		eventMap.descriptionText = desc
	}
	if (unit) {
		eventMap.unit = unit
	}	
	logTrace "Creating Event: ${eventMap}"
	return eventMap
}

private wakeUpIntervalSetCmd(secondsVal) {
    return secureCmd(zwave.wakeUpV2.wakeUpIntervalSet(seconds:secondsVal, nodeid:zwaveHubNodeId))
	// return zwave.wakeUpV2.wakeUpIntervalSet(seconds:(minutesVal * 60), nodeid:zwaveHubNodeId).format()
}

private wakeUpNoMoreInfoCmd() {
	return secureCmd(zwave.wakeUpV2.wakeUpNoMoreInformation())
	// return zwave.wakeUpV2.wakeUpNoMoreInformation().format()
}

private wakeUpIntervalGetCmd() {
	return secureCmd(zwave.wakeUpV2.wakeUpIntervalGet());
}

private wakeUpIntervalCapabilitiesGetCmd() {
	return secureCmd(zwave.wakeUpV2.wakeUpIntervalCapabilitiesGet());
}

private batteryGetCmd() {
	return secureCmd(zwave.batteryV1.batteryGet())
	// return zwave.batteryV1.batteryGet().format()
}

private versionGetCmd() {
	return secureCmd(zwave.versionV1.versionGet())
    // return zwave.versionV1.versionGet().format()
}

private manufacturerSpecficGetCmd() {
	return secureCmd(zwave.manufacturerSpecificV2.manufacturerSpecificGet())
}

private firmwareMdGetCmd() { 
	return secureCmd(zwave.firmwareUpdateMdV2.firmwareMdGet())
}

private configGetCmd(paramNum) {
    return secureCmd(zwave.configurationV1.configurationGet(parameterNumber: paramNum))
	// return zwave.configurationV1.configurationGet(parameterNumber: paramNum).format()
}

private configSetDefaultCmd(paramNum, size) {
    return secureCmd(zwave.configurationV1.configurationSet(parameterNumber: paramNum, size: size, defaultValue:true)) 
}

private configSetCmd(paramNum, size, val) {
    return secureCmd(zwave.configurationV1.configurationSet(parameterNumber: paramNum, size: size, scaledConfigurationValue: val))
	// return zwave.configurationV1.configurationSet(parameterNumber: paramNum, size: size, scaledConfigurationValue: val).format()    
}

// TODO:
// NotificationSupportedReport
// heat: true 
// water: true
// burglar: true
private notificationSupportedGetCmd() {
	return secureCmd(zwave.notificationV3.notificationSupportedGet())
}

String lifelineAssociationGetCmd() {
	return secureCmd(zwave.associationV2.associationGet(groupingIdentifier: 1))
}

String lifelineAssociationSetCmd() {
	return secureCmd(zwave.associationV2.associationSet(groupingIdentifier: 1, nodeId: [zwaveHubNodeId]))
}

String associationGetCmd(group) {
	return secureCmd(zwave.associationV2.associationGet(groupingIdentifier: group))
}

private associationGroupingsGetCmd() {
	return secureCmd(zwave.associationV2.associationGroupingsGet())
}

private secureCmd(cmd) {
	if (zwaveInfo?.zw?.contains("s") || ("0x98" in device?.rawDescription?.split(" "))) {
		return zwave.securityV1.securityMessageEncapsulation().encapsulate(cmd).format()
	}
	else {
		return cmd.format()
	}
}

// How often should we request a battery report?
// If we don't have a battery report yet OR the report we have is over XX hours old, request one.
private canReportBattery() {
	// For now, its based on 24 hours.  This is a catch all as the device will send battery reports on its own 
    // at each wake up interval.
	def reportEveryMS = (24 * 60 * 60 * 1000)
		
	return (!state.lastBatteryReport || (now() - state.lastBatteryReport > reportEveryMS)) 
}


// Settings - Wake Up Interval / Battery Reporting Interval 
// Defaults to 1 hour.
// (in hours)
private getWakeUpIntervalSetting() {
	return settings?.wakeUpInterval
}

// Settings - Parameter 14: Enable/Disable Basic Set Command, defaults to 0 (disabled)
private getBasicSetCommandSetting() {
	return settings.basicSetCommand? 1 : 0
}

// Create a value for the basicSetCommand attribute.
private basicSetCommandDescription(val) {
	if (val == 0) {
    	return "disabled"
    } else {
        return "enabled"
    }
}

// Settings - Parameter 17: Leak Report Interval, defaults to 5.
private getLeakReportIntervalSetting() {
    // null is okay for this setting
	return settings?.leakReportInterval
}

// Create a value for the leakReportingInterval attribute.
private leakReportingIntervalDescription(val) {
	if (val == 0) {
    	return "DISABLED"
    } else if (val == 1) {
        return "${val} minute"
    } else {
        return "${val} minutes"
    }
}

// Settings - Parameter 18: Shock Sensor, defaults to 0 (off).
private getShockSensorSetting() {
	return settings?.shockSensor ? 1 : 0
}

// Settings - Parameter 19: Temperature Report Interval (0 = disable, 3-240 range in minutes * 10 (ie, 3 = 30 minutes).  Defaults to 0.)
private getTempReportingIntervalSetting() {
    return settings?.tempReportingInterval
	// return settings?.tempReporting ?: findDefaultOptionName(tempReportingOptions)
}

// Settings - Parameter 20:
private getTempTriggerHighValueSetting() {
	def val = settings?.tempTriggerHighValue
    
    if (val) {
    	val *= 10
    }
    
    return val
}

// Settings - Parameter 22:
private getTempTriggerLowValueSetting() {
	def val = settings?.tempTriggerLowValue
    
    if (val) {
    	val *= 10
    }
    
    return val
}


// Settings - Parameter 24: Blinking LED when Alarm Triggers (default to enable)
private getBlinkLEDAlarmSetting() {
	return settings?.blinkLEDAlarm ? 1 : 0
}

// Create a value for the tempReportingInterval attribute.
private tempReportingIntervalDescription(val) {
	if (val == 0) {
    	return "DISABLED"
    } else {
        return "${val * 10} minutes"
    }
}

// Create a value for the tempTriggerHighValue/tempTriggerLowValue attribute.
private tempTriggerValueDescription(val) {
	if (val == 0) {
    	return "DISABLED"
    } else {
        return "${val / 10}F"  // TODO: convert to C?
    }
}

    
// Configuration Parameters
// optional elements:
// .attribute     = Will send event with current value to that attribute
// .checkInterval = boolean to indicate if parameter effects checkInterval attribute
// .default       = default value
private getConfigData() {
	return [
        [paramNum: 14, name: "Enable Basic Set Command", value: basicSetCommandSetting, size: 1, attribute: "basicSetCommand", attrDesc: { int val -> basicSetCommandDescription(val) }],
        [paramNum: 17, name: "Leak Report Interval", value: leakReportIntervalSetting, size: 1, default: 5, attribute: "leakReportingInterval", attrDesc: { int val -> leakReportingIntervalDescription(val) } ],
        [paramNum: 18, name: "Enable Shock Sensor", value: shockSensorSetting, size:1],
    	// [paramNum: 19, name: "Temperature Reporting Interval", value: convertOptionSettingToInt(tempReportingOptions, tempReportingSetting), size: 1],
        [paramNum: 19, name: "Temperature Reporting Interval", value: tempReportingIntervalSetting, size: 1, attribute: "tempReportingInterval", attrDesc: { int val -> tempReportingIntervalDescription(val) }, checkInterval: true],
        [paramNum: 20, name: "High Temperature Trigger Value", value: tempTriggerHighValueSetting, size: 2, default: 1040, attribute: "tempTriggerHighValue", attrDesc: { int val -> tempTriggerValueDescription(val) }],
        [paramNum: 22, name: "Low Temperature Trigger Value", value: tempTriggerLowValueSetting, size: 2, default: 320, attribute: "tempTriggerLowValue", attrDesc: { int val -> tempTriggerValueDescription(val) }],
        [paramNum: 24, name: "Enable Blinking LED When Alarm Triggers", value: blinkLEDAlarmSetting, size:1]
	]	
}

// CSS: No longer used.  Here for reference for enum usage in prefs
private getTempReportingOptions() {
	[
    	[name: formatDefaultOptionName("Disabled"), value: 0],
		[name: "1 Minute", value: 1],
		[name: "2 Minutes", value: 2],
		[name: "3 Minutes", value: 3],
		[name: "4 Minutes", value: 4],
		[name: "5 Minutes", value: 5],
		[name: "10 Minutes", value: 10],
		[name: "30 Minutes", value: 30],
		[name: "1 Hour", value: 60],
		[name: "2 Hours", value: 120],
		[name: "4 Hours", value: 240],
		[name: "8 Hours", value: 480],
        [name: "12 Hours", value: 720],
        [name: "24 Hours", value: 1440]
	]
}

private convertOptionSettingToInt(options, settingVal) {
	return safeToInt(options?.find { "${settingVal}" == it.name }?.value, 0)
}

private formatDefaultOptionName(val) {
	return "${val}${defaultOptionSuffix}"
}

private findDefaultOptionName(options) {
	def option = options?.find { it.name?.contains("${defaultOptionSuffix}") }
	return option?.name ?: ""
}

private getDefaultOptionSuffix() {
	return "   (Default)"
}

private safeToInt(val, defaultVal=-1) {
	return "${val}"?.isInteger() ? "${val}".toInteger() : defaultVal
}

private safeToDec(val, defaultVal=-1) {
	return "${val}"?.isBigDecimal() ? "${val}".toBigDecimal() : defaultVal
}

private hexToInt(hex, size) {
	if (size == 2) {
		return hex[1] + (hex[0] * 0x100)
	}
	else {
		return hex[0]
	}
}

// TODO: is there a conversion to the "smartthings" standard date/time format, that would handle region translations?
private convertToLocalTimeString(dt) {
	def timeZoneId = location?.timeZone?.ID
	if (timeZoneId) {
        // return dt.format("MM/dd/yyyy hh:mm:ss a", TimeZone.getTimeZone(timeZoneId))
		return dt.format("yyyy-MM-dd hh:mm:ss a z", TimeZone.getTimeZone(timeZoneId))
	}
	else {
		return "$dt"
	}	
}

private isDuplicateCommand(lastExecuted, allowedMil) {
	!lastExecuted ? false : (lastExecuted + allowedMil > new Date().time) 
}

private logDebug(msg) {
	if (settings?.debugEnable || settings?.debugEnable == null) {
		log.debug "$msg"
	}
}

private logInfo(msg) {
	if (settings?.infoEnable || settings?.infoEnable == null) {
		log.info "$msg"
	}
}

private logTrace(msg) {
	log.trace "$msg"
}