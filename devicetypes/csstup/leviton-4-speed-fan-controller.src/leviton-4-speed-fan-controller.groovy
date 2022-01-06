/* Leviton 4 speed zwave fan controller.
 *
 * ZW4SF
 * 
 * Original 
 */

/* Original details and license: */
/**
 *  Copyright 2018 SmartThings
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
 */
import groovy.transform.Field

@Field static Map commandClassVersions = [
	0x20: 2,	// Basic
	0x25: 1,	// Switch Binary
    0x26: 3,    // S Switch Multilevel (device supports V4)
    0x2B: 1,    // S Scene Activation
    0x2C: 1,    // S Scene Actuator Conf
	0x55: 2,	// * Transport Service 
	0x59: 3,	// S Association Group Information 
	0x5A: 1,	// S DeviceResetLocally
	0x5E: 2,	// * ZwaveplusInfo
	0x6C: 1,	// * Supervision
	0x70: 3,	// S Configuration (device supports V4)
	0x72: 2,	// S ManufacturerSpecific
	0x73: 1,	// S Powerlevel
	0x7A: 5,	// S Firmware Update Meta-Data
	0x85: 2,	// S Association
	0x86: 1,	// S Version (Device supports V3)
    0x87: 3,    // S Indicator
	0x8E: 3,	// S Multi Channel Association
	0x98: 1,	// * Security S0
	0x9F: 1		// * Security S2
]

// cc:5E,55,98,9F,6C sec:85,8E,59,86,72,5A,87,73,7A,26,2B,2C,70
 
metadata {
	definition(
            name: "Leviton 4-Speed Fan Controller", 
            namespace: "csstup", 
            author: "coreystup@gmail.com", 
            ocfDeviceType: "oic.d.fan", 
            genericHandler: "Z-Wave",
            mnmn: "SmartThings",
            runLocally: false
       ) {
		capability "Switch Level"     // "level" attribute and setLevel() function
		capability "Switch"
		capability "Fan Speed"        // "fanSpeed" attribute and setFanSpeed() function
		capability "Health Check"
		capability "Actuator"         // Deprecated
		capability "Refresh"          // Send the refresh command to the device
		capability "Sensor"           // Deprecated

		command "low"
		command "medium"
		command "high"
		command "raiseFanSpeed"
		command "lowerFanSpeed"
        
        attribute "lastCheckIn", "string"
        attribute "lastEvent", "String"

		fingerprint mfr: "001D", prod: "0038", model: "0002", deviceJoinName: "Leviton Fan", mnmn: "SmartThings", vid: "SmartThings-smartthings-Z-Wave_Fan_Controller_4_Speed" //Leviton 4-Speed Fan Controller
	}

	simulator {
		status "00%": "command: 2003, payload: 00"
		status "33%": "command: 2003, payload: 21"
		status "66%": "command: 2003, payload: 42"
		status "99%": "command: 2003, payload: 63"
	}

	tiles(scale: 2) {
		multiAttributeTile(name: "fanSpeed", type: "generic", width: 6, height: 4, canChangeIcon: true) {
			tileAttribute("device.fanSpeed", key: "PRIMARY_CONTROL") {
				attributeState "0", label: "off", action: "switch.on", icon: "st.thermostat.fan-off", backgroundColor: "#ffffff"
				attributeState "1", label: "low", action: "switch.off", icon: "st.thermostat.fan-on", backgroundColor: "#00a0dc"
				attributeState "2", label: "medium", action: "switch.off", icon: "st.thermostat.fan-on", backgroundColor: "#00a0dc"
				attributeState "3", label: "high", action: "switch.off", icon: "st.thermostat.fan-on", backgroundColor: "#00a0dc"
			}
			tileAttribute("device.fanSpeed", key: "VALUE_CONTROL") {
				attributeState "VALUE_UP", action: "raiseFanSpeed"
				attributeState "VALUE_DOWN", action: "lowerFanSpeed"
			}
		}

		standardTile("refresh", "device.switch", width: 2, height: 2, inactiveLabel: false, decoration: "flat") {
			state "default", action: "refresh.refresh", icon: "st.secondary.refresh"
		}
        
		main "fanSpeed"
		details(["fanSpeed", "refresh"])
	}
    
    preferences {
		configParams.each {
			getOptionsInput(it)
		}
        input (name: "debugLoggingEnabled", type: "bool", title: "Debug Logging?", required: true, defaultValue: false, displayDuringSetup: false)
	}
}

private getOptionsInput(param) {
	if (param.options) {
		input "configParam${param.num}", "enum",
			title: "${param.name}:",
			required: false,
			defaultValue: param.value?.toString(),
			displayDuringSetup: true,
			options: param.options
	}
	else if (param.range) {
		input "configParam${param.num}", "number",
			title: "${param.name}:",
			required: false,
			defaultValue: param.value?.toString(),
			displayDuringSetup: true,
			range: param.range
	}
}

def installed() {
	sendEvent(name: "checkInterval", value: 2 * 15 * 60 + 2 * 60, displayed: false, data: [protocol: "zwave", hubHardwareId: device.hub.hardwareID])
	response(refresh())
}

def updated() {
	logTrace "updated()" 
	if (!isDuplicateCommand(state.lastUpdated, 3000)) {
		state.lastUpdated = new Date().time

		// initialize()

		executeConfigureCmds()
	}
    refresh()
}

def parse(String description) {
    logTrace "parse() ${description}"
	def result = null
	def cmd = zwave.parse(description, commandClassVersions)
	if (cmd) {
		result = zwaveEvent(cmd)
	}
	else {
		log.warn "Unable to parse: $description"
	}    
    
    logDebug "Parse returned ${result?.descriptionText}"

    updateLastCheckIn()
    
	return result
}

void updateLastCheckIn() {
	if (!isDuplicateCommand(state.lastCheckInTime, 60000)) {
		state.lastCheckInTime = new Date().time

		sendEvent(name: "lastCheckIn", value: convertToLocalTimeString(new Date()), displayed: false)
	}
}

def zwaveEvent(physicalgraph.zwave.commands.basicv1.BasicReport cmd) {
    logDebug ("BasicReport- $cmd")
	fanEvents(cmd)
}

def zwaveEvent(physicalgraph.zwave.commands.basicv1.BasicSet cmd) {
	logDebug ("BasicSet- $cmd")
	fanEvents(cmd)
}

def zwaveEvent(physicalgraph.zwave.commands.switchmultilevelv3.SwitchMultilevelReport cmd) {
	logDebug ("SwitchMultilevelReport - $cmd")
	fanEvents(cmd)
}

def zwaveEvent(physicalgraph.zwave.commands.switchmultilevelv3.SwitchMultilevelSet cmd) {
	logDebug ("SwitchMultilevelSet- $cmd")
	fanEvents(cmd)
}

def zwaveEvent(physicalgraph.zwave.commands.versionv1.VersionReport cmd) {
	logTrace "VersionReport: ${cmd}"

	def subVersion = String.format("%02d", cmd.applicationSubVersion)
	def fullVersion = "${cmd.applicationVersion}.${subVersion}"
    
    updateDataValue("firmwareVersion", fullVersion)
    updateDataValue("zWaveProtocolVersion", "${cmd.zWaveProtocolVersion}.${cmd.zWaveProtocolSubVersion}")

	if (fullVersion != device.currentValue("firmwareVersion")) {
		// sendEvent(getEventMap("firmwareVersion", fullVersion))
	}
	return []
}

def zwaveEvent(physicalgraph.zwave.commands.configurationv3.ConfigurationReport cmd) {
	logTrace ("ConfigurationReport: $cmd")
	state.configured = true

	updateSyncStatus("Syncing...")
	runIn(10, updateSyncStatus)

	def param = configParams.find { it.num == cmd.parameterNumber }
	if (param) {
		logDebug "${param.name}(#${param.num}) = ${cmd.scaledConfigurationValue}"
		setParamStoredValue(param.num, cmd.scaledConfigurationValue)
	} else {
		logDebug "Unknown Parameter #${cmd.parameterNumber} = ${cmd.scaledConfigurationValue}"
	}
	state.resyncAll = false
	return []
}

def updateSyncStatus(status=null) {
	if (status == null) {
		def changes = getPendingChanges()
		if (changes > 0) {
			status = "${changes} Pending Change" + ((changes > 1) ? "s" : "")
		}
		else {
			status = "Synced"
		}
	}
	if (device.currentValue("syncStatus") != status) {
		sendEvent(getEventMap("syncStatus", status, false))
	}
}

private getPendingChanges() {
	return (configParams.count { isConfigParamSynced(it) ? 0 : 1 })
}

private isConfigParamSynced(param) {
	return (!isParamSupported(param) || param.value == getParamStoredValue(param.num))
}

private isParamSupported(param) {
	return (!param.firmware || param.firmware <= firmwareVersion)
}

private getParamStoredValue(paramNum) {
	return safeToInt(state["configVal${paramNum}"], null)
}

private setParamStoredValue(paramNum, value) {
	state["configVal${paramNum}"] = value
}


// MSR handler
def zwaveEvent(physicalgraph.zwave.commands.manufacturerspecificv2.ManufacturerSpecificReport cmd) {
	logTrace "ManufacturerSpecificReport: ${cmd}"
    def msr = String.format("%04X-%04X-%04X", cmd.manufacturerId, cmd.productTypeId, cmd.productId)
    updateDataValue("MSR", msr)
    updateDataValue("manufacturer", cmd.manufacturerName)
}


def zwaveEvent(physicalgraph.zwave.Command cmd) {
	// Handles all Z-Wave commands we aren't interested in
	log.debug "Unhandled: ${cmd.toString()}"
	[:]
}

// Log event data as info ("device is XXX")
def infoCreateEvent(Map map) {
	def event = createEvent(map)
    log.info "${event?.descriptionText}"
    return event
}

def fanEvents(physicalgraph.zwave.Command cmd) {
	def rawLevel = cmd.value as int
	def result = []

	if (0 <= rawLevel && rawLevel <= 100) {
		def value = (rawLevel ? "on" : "off")
        
		result << infoCreateEvent(name: "switch", value: value)
		result << infoCreateEvent(name: "level", value: rawLevel == 99 ? 100 : rawLevel, displayed: false)

		// Report speeds 0 - 4 (0 = off, 1-4 on speeds)
		def fanSpeed = getFanSpeedFor4SpeedDevice(rawLevel)
		result << infoCreateEvent(name: "fanSpeed", value: fanSpeed)
	}

	return result
}

def on() {
	state.lastOnCommand = now()
	delayBetween([
			zwave.switchMultilevelV3.switchMultilevelSet(value: 0xFF).format(),
			zwave.switchMultilevelV3.switchMultilevelGet().format()
		], 1000)  // 5000
}

def off() {
	delayBetween([
			zwave.switchMultilevelV3.switchMultilevelSet(value: 0x00).format(),
			zwave.switchMultilevelV3.switchMultilevelGet().format()
		], 1000)   // 2000
}

def getDelay() {
	// the leviton is comparatively well-behaved, but the GE and Honeywell devices are not
	zwaveInfo.mfr == "001D" ? 2000 : 5000
}

// Called from Switch Level capability
def setLevel(value, rate = null) {
	def cmds = []
	def timeNow = now()

	if (state.lastOnCommand && timeNow - state.lastOnCommand < delay ) {
		// because some devices cannot handle commands in quick succession, this will delay the setLevel command by a max of 2s
		log.debug "command delay ${delay - (timeNow - state.lastOnCommand)}"
		cmds << "delay ${delay - (timeNow - state.lastOnCommand)}"
	}

	def level = value as Integer
	level = level == 255 ? level : Math.max(Math.min(level, 99), 0)
	log.debug "setLevel >> value: $level"

	cmds << delayBetween([
				zwave.switchMultilevelV3.switchMultilevelSet(value: level).format(),
				zwave.switchMultilevelV3.switchMultilevelGet().format()
			], 1000)

	return cmds
}

// Called from "Fan Speed" capability
def setFanSpeed(speed) {
	if (speed as Integer == 0) {
		off()
	} else if (speed as Integer == 1) {
		low()
	} else if (speed as Integer == 2) {
		medium()
	} else if (speed as Integer == 3) {
		high()
	} else if (speed as Integer == 4) {
		max()
	}
}

def raiseFanSpeed() {
	setFanSpeed(Math.min((device.currentValue("fanSpeed") as Integer) + 1, 3))
}

def lowerFanSpeed() {
	setFanSpeed(Math.max((device.currentValue("fanSpeed") as Integer) - 1, 0))
}

def low() {
	setLevel(25)
}

def medium() {
	setLevel(50)
}

def high() {
	setLevel(75)
}

def max() {
	setLevel(99)
}

def refresh() {
	logDebug "refresh()..."

	sendCommands([
		switchMultilevelGetCmd(),
		versionGetCmd()
	])
    
    state.lastRefresh = new Date().time

	return []
}

void sendCommands(List<String> cmds, Integer delay=500) {
	if (cmds) {
		def actions = []
		cmds.each {
			actions << new physicalgraph.device.HubAction(it)
		}
		sendHubCommand(actions, delay)
	}
}

def executeConfigureCmds() {	
	// runIn(6, updateSyncStatus)
	
	def cmds = []

	if (state.resyncAll || !device.currentValue("firmwareVersion")) {
		cmds << versionGetCmd()
	}

	configParams.each {
		if (isParamSupported(it)) {
			def storedVal = getParamStoredValue(it.num)
			if (state.resyncAll || "${storedVal}" != "${it.value}") {
				if (state.configured) {
					logDebug "CHANGING ${it.name}(#${it.num}) from ${storedVal} to ${it.value}"
					cmds << configSetCmd(it)
				}
				cmds << configGetCmd(it)
			}
		}
	}

	if (cmds) {
		sendCommands(delayBetween(cmds, 250))
	}
}

def ping() {
	log.debug "ping()"
	return [ switchMultilevelGetCmd() ]
}

def getFanSpeedFor4SpeedDevice(rawLevel) {
	if (rawLevel == 0) {
		return 0
	} else if (1 <= rawLevel && rawLevel <= 25) {
		return 1
	} else if (26 <= rawLevel && rawLevel <= 50) {
		return 2
	} else if (51 <= rawLevel && rawLevel <= 75) {
		return 3
	} else if (76 <= rawLevel && rawLevel <= 100) {
		return 4
	}
}

// Configuration Parameters
private getConfigParams() {
	return [
    	minimumFanSpeedLevelParam,          // Parameter 3: Minimum fan speed level
        maximumFanSpeedLevelParam,          // Parameter 4: Maximum fan speed level
        presetFanSpeedLevelParam,           // Parameter 5: Preset fan speed level
        ledLevelIndicatorTimeoutParam,  	// Parameter 6: LED level indicator timeout 
		statusLEDConfigurationParam,  		// Parameter 7: Status LED configuration 
	]
}

// Parameter 3 - Minimum Fan Speed evel
// Valid Values = 0 to 99 (default 10)
private getMinimumFanSpeedLevelParam() { 
	return getParam(3, "Minimum Fan Speed Level (0-99)", 1, 10, null, "0..99")
}

// Parameter 4 - Maximum Fan Speed level
// Valid Values = 0 to 99 (default 99)
private getMaximumFanSpeedLevelParam() { 
	return getParam(4, "Maximum Fan Speed Level (0-99)", 1, 99, null, "0..99")
}

// Parameter 5 - Preset Fan Speed level
// Valid Values = 0 to 99 (default 0)
// 0    = Memory dim - last dim state (return to last speed)
// 1-99 = level
private getPresetFanSpeedLevelParam() { 
	return getParam(5, "Preset Fan Speed Level (0=Last State 1-99=Level)", 1, 99, null, "0..99")
}

// Parameter 6 - LED Level Indicator Timeout
// Valid Values = 0 to 255 (default 3)
//  0       = Level indicator always off
//  1 - 254 = Level indicator timeout (in seconds)
//  255     = Levels always on
private getLedLevelIndicatorTimeoutParam() { 
	return getParam(6, "LED Level Indicator Timeout (0=Always off, 1-254 = timeout (in seconds), 255=Always on", 1, 3, null, "0..255")
}

// Parameter 7 - Status LED Configuration
//   0  - LED Off
//  254 - Status Mode 
//  255 - Locater Mode (default)
private getStatusLEDConfigurationParam() {
	def options = [
		0  :"Status LED off",
		254:"Status LED on when fan is on",
		255:"Status LED on when fan is off"
	]
    // getParam(num, name, size, defaultVal,
	return getParam(7, "Status LED Configuration", 1, 255, options)
}

private getParam(num, name, size, defaultVal, options=null, range=null, firmware=null) {
	def val = safeToInt((settings ? settings["configParam${num}"] : null), defaultVal)

	def map = [num: num, name: name, size: size, value: val]
	if (options) {
		map.options = setDefaultOption(options, defaultVal)
	}

	if (range) map.range = range

	if (firmware) map.firmware = firmware

	return map
}

private setDefaultOption(options, defaultVal) {	
	options?.each {
		if (it.key == defaultVal) {
			it.value = "${it.value} [DEFAULT]"
		}
	}	
	return options
}

private versionGetCmd() {
	return secureCmd(zwave.versionV1.versionGet())
}

String switchMultilevelGetCmd() {
	return secureCmd(zwave.switchMultilevelV3.switchMultilevelGet())
}

private configSetCmd(param) {
	return secureCmd(zwave.configurationV2.configurationSet(parameterNumber: param.num, size: param.size, scaledConfigurationValue: param.value))
}

private configGetCmd(param) {
	return secureCmd(zwave.configurationV2.configurationGet(parameterNumber: param.num))
}

String secureCmd(cmd) {
	try {
		if (zwaveInfo?.zw?.contains("s") || ("0x98" in device?.rawDescription?.split(" "))) {
			return zwave.securityV1.securityMessageEncapsulation().encapsulate(cmd).format()
		}
		else {
			return cmd.format()
		}
	}
	catch (ex) {
		return cmd.format()
	}
}

private getEventMap(name, value, displayed=true) {
	def desc = "${device.displayName}: ${name} is ${value}"

	def eventMap = [
		name: name,
		value: value,
		displayed: displayed,
		descriptionText: "${desc}"
	]

	if (displayed) {
		logDebug "${desc}"
	}
	else {
		logTrace "${desc}"
	}
	return eventMap
}

private getFirmwareVersion() {
	return safeToDec(device.currentValue("firmwareVersion"))
}

private safeToInt(val, defaultVal=0) {
	if ("${val}"?.isInteger()) {
		return "${val}".toInteger()
	}
	else if ("${val}".isDouble()) {
		return "${val}".toDouble()?.round()
	}
	else {
		return  defaultVal
	}
}

private safeToDec(val, defaultVal=0) {
	return "${val}"?.isBigDecimal() ? "${val}".toBigDecimal() : defaultVal
}

private convertToLocalTimeString(dt) {
	def timeZoneId = location?.timeZone?.ID
	if (timeZoneId) {
		return dt.format("MM/dd/yyyy hh:mm:ss a", TimeZone.getTimeZone(timeZoneId))
	}
	else {
		return "$dt"
	}
}

private isDuplicateCommand(lastExecuted, allowedMil) {
	!lastExecuted ? false : (lastExecuted + allowedMil > new Date().time)
}

void logDebug(String msg) {
	if (state.debugLoggingEnabled) {
		log.debug(msg)
	}
}

void logTrace(String msg) {
     log.trace(msg)
}