/**
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
metadata {
	definition(name: "Eaton Z-Wave Plus Wireless Plug-in Module ", 
    			namespace: "csstup", author: "coreystup@gmail.com", ocfDeviceType: "oic.d.smartplug", 
                runLocally: false, minHubCoreVersion: '000.019.00012', executeCommandsLocally: false) {
		capability "Switch"
		capability "Refresh"    // Allow the execution of the refresh command for devices that support it
		capability "Actuator"
		capability "Sensor"     // In SmartThings terms, it represents that a Device has attributes
		capability "Health Check"

		attribute "lastCheckin", "string"

		// zw:L type:1001 mfr:001A prod:0053 model:0050 ver:1.01 zwv:4.61 lib:03 cc:5E,85,59,55,86,72,5A,73,98,9F,6C,7A,71,25,27,70
		// fingerprint mfr: "001A", prod: "0053", model: "0050", deviceJoinName: "Eaton Z-Wave Plus Plug-In Module" // RF96APM - Z-WAVE PLUS WIRELESS ON/OFF PLUG-IN MODULE
	}

	simulator {
	}

	tiles {
		standardTile("switch", "device.switch", width: 2, height: 2, canChangeIcon: true) {
			state "on", label: '${name}', action: "switch.off", icon: "st.switches.switch.on", backgroundColor: "#00a0dc"
			state "off", label: '${name}', action: "switch.on", icon: "st.switches.switch.off", backgroundColor: "#ffffff"
		}
		standardTile("refresh", "device.switch", inactiveLabel: false, decoration: "flat") {
			state "default", label: '', action: "refresh.refresh", icon: "st.secondary.refresh"
		}

		main "switch"
		details(["switch", "refresh"])
	}
}

def installed() {
	// Device-Watch simply pings if no device events received for checkInterval duration of 32min = 2 * 15min + 2min lag time
	sendEvent(name: "checkInterval", value: 2 * 15 * 60 + 2 * 60, displayed: false, data: [protocol: "zwave", hubHardwareId: device.hub.hardwareID, offlinePingable: "1"])
}

def configure() {
}

def updated() {
	logTrace "updated()"
	response(refresh())
}

def parse(String description) {	
	def result = []
	def cmd = zwave.parse(description, commandClassVersions)
	if (cmd) {
		result += zwaveEvent(cmd)
        logTrace("'$description' parsed to $result")
	} else {
		log.debug("Couldn't zwave.parse '$description'")
	}
		
	if (!isDuplicateCommand(state.lastCheckinTime, 60000)) {
		state.lastCheckinTime = new Date().time
		result << createEvent( name: "lastCheckin", value: convertToLocalTimeString(new Date()), displayed: false)
	}
	return result
}

def parseNOLONGERUSED(description) {
	def result = null
	if (description.startsWith("Err 106")) {
		result = createEvent(descriptionText: description, isStateChange: true)
	} else if (description != "updated") {
		def cmd = zwave.parse(description, getCommandClassVersions())
		if (cmd) {
			result = zwaveEvent(cmd)
			log.debug("'$description' parsed to $result")
		} else {
			log.debug("Couldn't zwave.parse '$description'")
		}
	}
	result
}

private getCommandClassVersions() {
	[
        0x25: 1,  // Switch Binary
        0x27: 1,  // All Switch
        0x55: 1,  // Transport Service
        0x59: 1,  // AssociationGrpInfo
        0x5A: 1,  // DeviceResetLocally
        0x5E: 2,  // ZwaveplusInfo
        0x6C: 1,  // Supervision
        0x70: 1,  // Configuration
        0x71: 3,  // Notification (Device supports V5)
        0x72: 2,  // ManufacturerSpecific
        0x73: 1,  // Powerlevel
        0x7A: 1,  // Firmware Update MD
        0x85: 2,  // Association
        0x86: 1,  // Version (Device supports V2) 
        0x98: 1,  // Security
        0x9F: 1	  // Security 2
	]
}

def zwaveEvent(physicalgraph.zwave.commands.basicv1.BasicReport cmd) {
	createEvent(name: "switch", value: cmd.value ? "on" : "off")
}

def zwaveEvent(physicalgraph.zwave.commands.basicv1.BasicSet cmd) {
	createEvent(name: "switch", value: cmd.value ? "on" : "off")
}

def zwaveEvent(physicalgraph.zwave.commands.switchbinaryv1.SwitchBinaryReport cmd) {
	createEvent(name: "switch", value: cmd.value ? "on" : "off")
}

//def zwaveEvent(physicalgraph.zwave.commands.securityv1.SecurityMessageEncapsulation cmd) {
//	def encapsulatedCommand = cmd.encapsulatedCommand([0x20: 1, 0x25: 1])
//	if (encapsulatedCommand) {
//		zwaveEvent(encapsulatedCommand)
//	}
//}

def zwaveEvent(physicalgraph.zwave.commands.securityv1.SecurityMessageEncapsulation cmd) {
	def encapsulatedCmd = cmd.encapsulatedCommand(commandClassVersions)	
	
	def result = []
	if (encapsulatedCmd) {
		result += zwaveEvent(encapsulatedCmd)
	}
	else {
		log.warn "Unable to extract encapsulated cmd from $cmd"
	}
	return result
}

def zwaveEvent(physicalgraph.zwave.commands.manufacturerspecificv2.ManufacturerSpecificReport cmd) {
	log.debug "manufacturerId:   $cmd.manufacturerId"
	log.debug "manufacturerName: $cmd.manufacturerName"
	log.debug "productId:        $cmd.productId"
	log.debug "productTypeId:    $cmd.productTypeId"
	def msr = String.format("%04X-%04X-%04X", cmd.manufacturerId, cmd.productTypeId, cmd.productId)
	updateDataValue("MSR", msr)
	updateDataValue("manufacturer", cmd.manufacturerName)
	createEvent([descriptionText: "$device.displayName MSR: $msr", isStateChange: false])
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

def zwaveEvent(physicalgraph.zwave.commands.associationv2.AssociationGroupingsReport cmd) {
    logTrace "AssociationGroupingsReport ${cmd}"
    sendEvent(name: "groups", value: cmd.supportedGroupings)
    logDebug "Supported association groups: ${cmd.supportedGroupings}"
    state.associationGroups = cmd.supportedGroupings
}

def zwaveEvent(physicalgraph.zwave.commands.versionv1.VersionCommandClassReport cmd) {
	if (cmd.commandClassVersion) {
    	def cls = String.format("%02X", cmd.requestedCommandClass)
        state.ccVersions[cls] = cmd.commandClassVersion
		createEvent(name: "ccVersions", value: util.toJson(state.ccVersions), displayed: false, descriptionText:"")
	} else {
    		[]
    }
}

def zwaveEvent(physicalgraph.zwave.Command cmd) {
	log.debug "Unhandled: $cmd"
	null
}

def on() {
	commands([
        zwave.switchBinaryV1.switchBinarySet(switchValue: 0xFF),
        // We may not need to ask it, it just tells us via a report.
        // zwave.switchBinaryV1.switchBinaryGet()
	])
}

def off() {
	commands([
		zwave.switchBinaryV1.switchBinarySet(switchValue: 0x00),
		// zwave.switchBinaryV1.switchBinaryGet()
	])
}

def ping() {
	logTrace "ping()"
	refresh()
}

def poll() {
	logTrace "poll()"
	refresh()
}

def refresh() {
	logTrace "refresh()"
	def cmds = []
    cmds << zwave.associationV2.associationGroupingsGet()
    cmds << zwave.configurationV1.configurationGet(parameterNumber: 13)
	cmds << zwave.switchBinaryV1.switchBinaryGet()
	if (getDataValue("MSR") == null) {
		cmds << zwave.manufacturerSpecificV1.manufacturerSpecificGet()
	}
    
    if (state.lifelineAssoc != true) {
		logTrace "Setting lifeline Association DEBUG2"
        cmds << zwave.associationV2.associationSet(groupingIdentifier: 1, nodeId: [zwaveHubNodeId])
        cmds << zwave.associationV2.associationGet(groupingIdentifier: 1)
        
        cmds << zwave.associationV2.associationGet(groupingIdentifier: 2)
		// cmds << lifelineAssociationSetCmd()
		// cmds << lifelineAssociationGetCmd()
	}
    
    // getSupportedCmdClasses().each {cc ->
	//		cmds << zwave.versionV1.versionCommandClassGet(requestedCommandClass: cc)
            
    cmds << zwave.switchBinaryV1.switchBinaryGet()
    logTrace(cmds)
    
	def output = commands(cmds)
    logTrace(output)
	output
}

private command(physicalgraph.zwave.Command cmd) {
	if ((zwaveInfo.zw == null && state.sec != 0) || zwaveInfo?.zw?.contains("s")) {
		zwave.securityV1.securityMessageEncapsulation().encapsulate(cmd).format()
	} else {
		cmd.format()
	}
}

private commands(commands, delay = 500) {
	delayBetween(commands.collect { command(it) }, delay)
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

private logDebug(msg) {
	if (debugOutputSetting) {
		log.debug "$msg"
	}
}

private logTrace(msg) {
	log.trace "$msg"
}