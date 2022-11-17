/*

 Ecolink garage door sensor
 
 Wake up Interval
 Device defaults to 12 hour wake up interval.  This value is adjustable from 3600 seconds (1 hour) to 604800 seconds (1 week) in 200 second increments.
 It rounds down to the nearest 3600 + 200 second interval (entering 3605 == 3600, 3799 = 3600, 3805 = 3800, etc)
 This DTH offers its parameter in minutes to make the input easier.
 
 Parameters:
 Parameter 1 configures the sensor to send or not send Basic Set commands of 0x00 to nodes in Association group 2
 turning the devices off when the sensor is in a restored state i.e. the door is closed. 
 By default the sensor does NOT send Basic Set commands to Association Group 2.  of 0x00. 
 
 Parameter 2 configures the sensor to either to send or not to send Sensor Binary Report commands to Association Group 1 when the sensor is faulted and
 restored.  This is in addition to the Notification events sent as well.
 Having the dual messages for each state change may be a feature to reduce the chances of a lost message at the cost of slightly more battery usage 
 and traffic.  
      
 
 Association
 This sensor has TWO Association groups of 5 nodes each. 
 
 Group one is a lifeline group who will receive unsolicited messages relating to door/window open/close notifications 
  (because there is no association group for tilt switches), case tampering notifications, low-battery notifications, and sensor binary reports. 
  
 Group 2 is intended for devices that are to be controlled i.e. turned on or off (on only by default) with a Basic Set.
 
 On inclusion the controller is added to group 1 (lifeline).
 
 Version history:
 01/08/2022 - V0.8   - Initial version
 
 */
 
/*
 *  Original ideas taken from
 *  https://github.com/SmartThingsCommunity/SmartThingsPublic/blob/master/devicetypes/smartthings/zwave-door-window-sensor.src/zwave-door-window-sensor.groovy
 *  Copyright 2015 SmartThings
 *  Z-Wave Door/Window Sensor
 *  Date: 2013-11-3
 */

metadata {
	definition(name: "Ecolink Garage Door Sensor", namespace: "smartthings", author: "SmartThings", ocfDeviceType: "x.com.st.d.sensor.contact", runLocally: false, executeCommandsLocally: false, genericHandler: "Z-Wave") {
		capability "Contact Sensor"
		capability "Sensor"
		capability "Battery"
		capability "Configuration"
		capability "Health Check"
		capability "Tamper Alert"
        capability "Refresh"
        
        attribute "lastWakeUpDate", "string"                // Date/time string of last wake up 
	    attribute "lastBatteryReportDate", "string"         // Date/time string of last battery report
        attribute "lastCheckIn", "string"                   // Date/Time string of last data received
        
        attribute "wakeUpInterval", "string"                // Wake up interval from the device (in seconds)

		// fingerprint mfr: "014A", prod: "0001", model: "0003", deviceJoinName: "Ecolink Open/Closed Sensor" //Ecolink Tilt Sensor
		
        // fingerprint mfr: "014A", prod: "0004", model: "0003", deviceJoinName: "Ecolink Garage Door Sensor" //Ecolink Tilt Sensor
	}

	// simulator metadata
	simulator {
		// status messages
		status "open": "command: 2001, payload: FF"
		status "closed": "command: 2001, payload: 00"
		status "wake up": "command: 8407, payload: "
	}

	// UI tile definitions
	tiles(scale: 2) {
		multiAttributeTile(name: "contact", type: "generic", width: 6, height: 4) {
			tileAttribute("device.contact", key: "PRIMARY_CONTROL") {
				attributeState("open", label: '${name}', icon: "st.contact.contact.open", backgroundColor: "#e86d13")
				attributeState("closed", label: '${name}', icon: "st.contact.contact.closed", backgroundColor: "#00A0DC")
			}
		}
		valueTile("battery", "device.battery", inactiveLabel: false, decoration: "flat", width: 2, height: 2) {
			state "battery", label: '${currentValue}% battery', unit: ""
		}
        
        valueTile("tamper", "device.tamper", height: 2, width: 2, decoration: "flat") {
			state "clear", label: 'tamper clear', backgroundColor: "#ffffff"
			state "detected", label: 'tampered', backgroundColor: "#ff0000"
		}
        
        standardTile("refresh", "device.refresh", width: 2, height: 2, decoration: "flat") {
			state "default", label: "Refresh", action: "refresh", icon:"${resourcesUrl}refresh.png"
		}
        
		main "contact"
		details(["contact", "refresh", "battery", "tamper"])
	}
    
    // Values are fetchable directly by referring to the input name.
	preferences {
        input (
            title: 'Z Wave Configuration and Parameters',
            description: 'Configurations available on the device',
            type: "paragraph",
            element: "paragraph"
        )
        
        // Wake Up Interval
        input (
            title: "Wake Up Interval",
            description: "Number of seconds between device wake ups.  Min = 3600 (1 hour).  Increments of 200 seconds.  Common Values:  3600 = 1 hour\n  7200 - 2 hours\n  14400 - 4 hours\nDefault 43200 (12 hours).",
            type: "paragraph",
            element: "paragraph"
        )
        input(
            name: "wakeUpInterval",
            type: "number",
            title: "Wake Up Interval",
            description: "Number of seconds between device wake ups.  Min = 3600 (1 hour).  Range 3600-604800.",
            range: "3600..604800",
            required: false
        )
        input(
            name: "batteryInterval",
            type: "number",
            title: "Battery Update Interval",
            description: "Number of minutes between battery updates. 0 = disabled.  Battery updates will be requested during the wake up check in, so battery checkins will be 'rounded' to next available wake up.",
            range: "0..43200",
            required: false
        )
        input(
            name: "disableSensorBinaryReports",
            type: "bool",
            title: "Disable Sensor Binary Reports?",
            description: "Disable additional sensor binary reports?",
            required: false,
            default: false
        )
    }
}

private getCommandClassVersions() {
	[
     0x20: 1,   // Basic
     0x30: 1,   // Sensor Binary
     0x59: 1,   // Association Group Info
     0x5E: 1,   // Zwave Plus Info
     0x70: 1,   // Configuration
     0x71: 3,   // Notification (device supports V4)
     0x72: 1,   // Manufacturer Specific
     0x73: 1,   // Powerlevel
     0x80: 1,   // Battery
     0x84: 1,   // Wakeup
     0x85: 1,   // Association
     0x86: 1,   // Version
    ]
}

def parse(String description) {
	def result = null
 
    def cmd = zwave.parse(description, commandClassVersions)
    if (cmd) {
        result = zwaveEvent(cmd)
    }
    
    updateLastCheckIn()

	log.debug "parsed '$description' to $result"
	return result
}

private updateLastCheckIn() {
	if (!isDuplicateCommand(state.lastCheckIn, 60000)) {
		state.lastCheckIn = new Date().time
		sendEvent(name: "lastCheckIn", value: convertToLocalTimeString(new Date()), displayed: false)
	}
}

def installed() {
	log.trace ("installed()")
    
	// this is the nuclear option because the device often goes to sleep before we can poll it
    // TODO: uncomment after testing
	// sendEvent(name: "contact", value: "open", descriptionText: "$device.displayName is open")
	sendEvent(name: "battery", unit: "%", value: 100)
	sendEvent(name: "tamper", value: "clear")
	response(initialPoll())
}

def refresh() {
	log.trace "refresh()"
    log.debug "state = $state"
    // sendEvent(name: "contact", value: "closed")
}
    
def updated() {
	// This method always gets called twice when preferences are saved.
	if (!isDuplicateCommand(state.lastUpdated, 3000)) {		
		state.lastUpdated = new Date().time
		logTrace "updated()"
    	log.info ("The configuration will be updated the next time the device wakes up.")
        state.updatePending = true
	}	
    
    // FOR EMERGENCY USE
    // sendEvent(name: "contact", value: "closed")
}

// configure() runs after installed() when a sensor is paired
def configure() {
	log.trace "configure()"
    
    // Set an initial device watch based on the default wake up interval (12 hours).  2 intervals, plus 5 minutes.
	sendEvent(name: "checkInterval", value: (12 * 60 * 60) * 2 + ( 5 * 60) , displayed: false, data: [protocol: "zwave", hubHardwareId: device.hub.hardwareID])
    
    def cmds = []

	cmds << zwave.configurationV1.configurationSet(parameterNumber: 2, size: 1, scaledConfigurationValue: (settings.disableSensorBinaryReports) ? 0x00 : 0xFF).format()
    
    if (state.lifelineAssoc != true) {
		logTrace "Setting lifeline Association"
		cmds << zwave.associationV2.associationSet(groupingIdentifier: 1, nodeId: [zwaveHubNodeId]).format()
		cmds << zwave.associationV2.associationGet(groupingIdentifier: 1).format()
	}
    
    if (cmds) {
		logDebug "configure() Sending configuration to device with 500ms delay between: ${cmds}"
		return delayBetween(cmds, 500)
	} else {
		return []
	}	
}

def sensorValueEvent(value) {
	if (value) {
		createEvent(name: "contact", value: "open")
	} else {
		createEvent(name: "contact", value: "closed")
	}
}

def zwaveEvent(physicalgraph.zwave.commands.basicv1.BasicReport cmd) {
	sensorValueEvent(cmd.value)
}

def zwaveEvent(physicalgraph.zwave.commands.basicv1.BasicSet cmd) {
	sensorValueEvent(cmd.value)
}

def zwaveEvent(physicalgraph.zwave.commands.sensorbinaryv1.SensorBinaryReport cmd) {
	sensorValueEvent(cmd.sensorValue)
}

def zwaveEvent(physicalgraph.zwave.commands.notificationv3.NotificationReport cmd) {
    log.trace("NotificationReport $cmd")
	def result = []
	if (cmd.notificationType == 0x06 && cmd.event == 0x16) {  // NOTIFICATION_TYPE_ACCESS_CONTROL 
		result << sensorValueEvent(1)  // Door is open
	} else if (cmd.notificationType == 0x06 && cmd.event == 0x17) {  // NOTIFICATION_TYPE_ACCESS_CONTROL 
		result << sensorValueEvent(0)  // Door is closed
	} else if (cmd.notificationType == 0x07) {  // NOTIFICATION_TYPE_BURGLAR 
	    if (cmd.event == 0x00) {
			result << createEvent(name: "tamper", value: "clear")
		} else if (cmd.event == 0x01 || cmd.event == 0x02) {
			result << sensorValueEvent(1)
		} else if (cmd.event == 0x03) {  // tamper - cover removed
			runIn(10, clearTamper, [overwrite: true, forceForLocallyExecuting: true])
			result << createEvent(name: "tamper", value: "detected", descriptionText: "$device.displayName was tampered")
		} else {
        	log.error ("Unknown event for $cmd") 
        }
    } else if (cmd.notificationType == 0x08 && cmd.event == 0x0B) {
    	// Emergency battery level report - replace battery now
    	result << createEvent(name: "battery", value: 1, unit: "%", displayed: true)
	} else {
		log.error ("Unknown notification for $cmd") 
	}
	result
}

def zwaveEvent(physicalgraph.zwave.commands.wakeupv1.WakeUpIntervalReport cmd) {
	log.trace ("WakeUpIntervalReport $cmd")
    
    state.wakeInterval = cmd.seconds
    
    // Because the device rounds the requested interval off, make sure that it matches.
    if ( settings.wakeUpInterval != cmd.seconds ) {
    	log.debug ("Changing prefences to match wake up interval")
        // updateSetting("wakeUpInterval", cmd.seconds )
    }
    
    // Update device watch.   Go for 2 periods + 5 minutes.
    sendEvent(name: "checkInterval", value: cmd.seconds * 2 + ( 5 * 60 ) , displayed: false, data: [protocol: "zwave", hubHardwareId: device.hub.hardwareID])
    
    createEvent(name: "wakeUpInterval", value: cmd.seconds, unit: "s")
}

def zwaveEvent(physicalgraph.zwave.commands.wakeupv1.WakeUpNotification cmd) {
    log.trace ("WakeUpNotification $cmd")
    
	def cmds = []
    
    if (state.updatePending) {
    	state.updatePending = false
        def sensorBinaryReportVal = (settings.disableSensorBinaryReports) ? 0xFF : 0x00
        log.debug ("Setting parameter 2 to ${sensorBinaryReportVal}")
    	cmds << zwave.configurationV1.configurationSet(parameterNumber: 2, size: 1, scaledConfigurationValue: sensorBinaryReportVal)
    }
    
	if (!state.MSR) {
		cmds << zwave.manufacturerSpecificV2.manufacturerSpecificGet()
	}
    
    if (!state.firmwareVersion) {
        cmds << zwave.versionV1.versionGet()
    }
    
    // The time between Wake-Up Notifications can be configured to be between 1 hour (3600) and 1 week (3600 * 24 * 7 ) with interval steps of 200 seconds. 
    // Default is 12 hours (43200 s)
    def wakeIntervalSeconds = settings.wakeUpInterval ? settings.wakeUpInterval.toInteger() : 43200
    // TODO: should we verify units of 200 seconds or will the device round for us
    
    if (state.wakeInterval != wakeIntervalSeconds ) {
        log.debug "Updating Wake Interval to ${wakeIntervalSeconds}"
        cmds << zwave.wakeUpV1.wakeUpIntervalSet(seconds: wakeIntervalSeconds, nodeid:zwaveHubNodeId)
        cmds << zwave.wakeUpV1.wakeUpIntervalGet()
    }

	if (device.currentValue("contact") == null) {
		// In case our initial request didn't make it, initial state check no. 3
		cmds << zwave.sensorBinaryV2.sensorBinaryGet(sensorType: zwave.sensorBinaryV2.SENSOR_TYPE_DOOR_WINDOW)
	}

    // Is it time to request a battery report?
    def batteryMinutes = (settings.batteryInterval != null) ? settings.batteryInterval : 1440
	if (!state.lastbat || 
         ( (batteryMinutes > 0) && (now() - state.lastbat > batteryMinutes * 60 * 1000) ) 
        ) {
		cmds << zwave.batteryV1.batteryGet()
	}   

	def request = []
	if (cmds.size() > 0) {
		request = commands(cmds, 1000)
		request << "delay 20000"
	}
	request << zwave.wakeUpV1.wakeUpNoMoreInformation().format()
    
    // Timestamp the last time we woke up.
    def event = createEvent(name: "lastWakeUpDate", value: convertToLocalTimeString(new Date()), displayed: false, isStateChange: true)
	state.lastWakeUpDate = now()
    
    // The fact that we got a wake up means the tamper is clear
    clearTamper()

	[event, response(request)]
}

def zwaveEvent(physicalgraph.zwave.commands.batteryv1.BatteryReport cmd) {
	def map = [name: "battery", unit: "%"]
	if (cmd.batteryLevel == 0xFF) {
		map.value = 1
		map.descriptionText = "${device.displayName} has a low battery"
		map.isStateChange = true
	} else {
		map.value = cmd.batteryLevel
	}
	state.lastbat = now()
    sendEvent(name: "lastBatteryReportDate", value: convertToLocalTimeString(new Date()), displayed: false, isStateChange: true)
    
	[createEvent(map)]
}

// Version Report handler
def zwaveEvent(physicalgraph.zwave.commands.versionv1.VersionReport cmd) {
	logTrace "VersionReport: ${cmd}"
	def subVersion = String.format("%02d", cmd.applicationSubVersion)
    updateDataValue("firmwareVersion", "${cmd.applicationVersion}.${subVersion}") 
	return []
}

def zwaveEvent(physicalgraph.zwave.commands.manufacturerspecificv1.ManufacturerSpecificReport cmd) {
	def msr = String.format("%04X-%04X-%04X", cmd.manufacturerId, cmd.productTypeId, cmd.productId)
	updateDataValue("MSR", msr)

	[]
}

def zwaveEvent(physicalgraph.zwave.commands.associationv2.AssociationReport cmd) {
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

def zwaveEvent(physicalgraph.zwave.commands.securityv1.SecurityMessageEncapsulation cmd) {
	def encapsulatedCommand = cmd.encapsulatedCommand(commandClassVersions)
	if (encapsulatedCommand) {
		zwaveEvent(encapsulatedCommand)
	}
}

def zwaveEvent(physicalgraph.zwave.Command cmd) {
    log.error ("unhandled command $cmd")
}

def initialPoll() {
	def request = []

	// check initial battery and contact state no.1
	request << zwave.batteryV1.batteryGet()
	request << zwave.sensorBinaryV2.sensorBinaryGet(sensorType: zwave.sensorBinaryV2.SENSOR_TYPE_DOOR_WINDOW)
	request << zwave.manufacturerSpecificV2.manufacturerSpecificGet()
	commands(request, 500) + ["delay 6000", command(zwave.wakeUpV1.wakeUpNoMoreInformation())]
}

private command(physicalgraph.zwave.Command cmd) {
	if ((zwaveInfo?.zw == null && state.sec != 0) || zwaveInfo?.zw?.contains("s")) {
		zwave.securityV1.securityMessageEncapsulation().encapsulate(cmd).format()
	} else {
		cmd.format()
	}
}

private commands(commands, delay = 200) {
	delayBetween(commands.collect { command(it) }, delay)
}

def clearTamper() {
	sendEvent(name: "tamper", value: "clear")
}

private convertToLocalTimeString(dt) {
	def timeZoneId = location?.timeZone?.ID
	if (timeZoneId) {
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
	//if (settings?.debugEnable || settings?.debugEnable == null) {
		log.debug "$msg"
	//}
}

private logInfo(msg) {
	//if (settings?.infoEnable || settings?.infoEnable == null) {
		log.info "$msg"
	//}
}

private logTrace(msg) {
	log.trace "$msg"
}