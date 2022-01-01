/*
 * Linkind Leak Sensor
 * 
 * Model on the device itself is "LS21001", but the device reports its model as "A001082".
 *
 * To pair this device, press and hold the button for 5+ seconds until the LED blinks quickly.  
 * Release the button, the LED will blink slowly indicating its ready to pair.
 *
 * Leak sensor only.  No temperature or humidity. 
 * Uses 2 AAA sized batteries
 * Has a 90db built in siren when a alarm condition is detected
 * Long poll checkins are set to every 1200 qtr-seconds (5 minutes).
 * It will flash the LED with Identify (cluster 0x0003) commads 
 * Water events:
 *   Registers as an IAS device.    Water events are sent as an IAS zone status update for alarm 1.
 *
 * Battery updates:
 *    The device allows for setting a report frequency for both battery voltage and battery percentage.  By default
 *    the reporting is disabled (no unsolicited battery reports).
 * 
 * Update history:
 * 12/01/2021 - V0.9   - Initial version
 *
 * Get updates from:
 * https://github.com/csstup/SmartThings-CSS/blob/master/devicetypes/csstup/linkind-leak-sensor.src/linkind-leak-sensor.groovy
 *
 * Original DTH code/concepts taken from:
 *  SmartSense Moisture Sensor
 *
 */
import physicalgraph.zigbee.clusters.iaszone.ZoneStatus
import physicalgraph.zigbee.zcl.DataType

metadata {
        definition(name: "Linkind Leak Sensor", namespace: "csstup", author: "coreystup@gmail.com", 
               runLocally: false, minHubCoreVersion: '000.017.0012', executeCommandsLocally: false, 
               mnmn: "SmartThings", vid: "generic-leak", genericHandler: "Zigbee") {
                capability "Configuration"
                capability "Battery"
                // capability "Refresh"
                capability "Water Sensor"
                capability "Health Check"
                capability "Sensor"
        
                attribute "lastCheckin", "String"
                attribute "batteryVoltage", "String"
                
                fingerprint endpointId: "01", profileId:"0104", inClusters: "0000,0001,0003,0020,0500,0B05", outClusters: "0019", model: "A001082", manufacturer: "LK", deviceJoinName: "Linkind Leak Sensor" 
        }

        simulator {
        }

        preferences {
            input "debugOutput", "bool", 
                title: "Enable debug logging?", 
                defaultValue: true, 
                required: false
        }

        tiles(scale: 2) {
                multiAttributeTile(name: "water", type: "generic", width: 6, height: 4) {
                        tileAttribute("device.water", key: "PRIMARY_CONTROL") {
                                attributeState "dry", label: "Dry", icon: "st.alarm.water.dry", backgroundColor: "#ffffff"
                                attributeState "wet", label: "Wet", icon: "st.alarm.water.wet", backgroundColor: "#00A0DC"
                        }
                }

                valueTile("battery", "device.battery", decoration: "flat", inactiveLabel: false, width: 2, height: 2) {
                        state "battery", label: '${currentValue}%', unit:"%"
                }
                standardTile("refresh", "device.refresh", inactiveLabel: false, decoration: "flat", width: 2, height: 2) {
                        state "default", action: "refresh.refresh", icon: "st.secondary.refresh"
                }

                main(["water"])
                details(["water", "battery", "refresh"])
        }
}

// Build a list of maps with parsed attributes
private List<Map> collectAttributes(Map descMap) {
        List<Map> descMaps = new ArrayList<Map>()

        descMaps.add(descMap)

        if (descMap.additionalAttrs) {
                descMaps.addAll(descMap.additionalAttrs)
        }

        return  descMaps
}

def getBATTERY_VOLTAGE_ATTR() { 0x0020 }
def getBATTERY_PERCENT_ATTR() { 0x0021 }

def parse(String description) {
    logDebug "parse() description: $description"

    // Determine current time and date in the user-selected date format and clock style
    def now = formatDate()    

    def result = []  // Create an empty result list
    def eventList = []  // A list of events, to be processed by createEvent

    if (description?.startsWith('zone status')) {
        eventList += parseIasMessage(description)
    } else {
        // parseDescriptionAsMap() can return additional attributes into the additionalAttrs map member
        Map descMap = zigbee.parseDescriptionAsMap(description)
        
        // logDebug "parse() descMap: ${descMap}"

        if (descMap?.clusterInt == 0x0000 && descMap.value) {  // Basic cluster responses
            switch (descMap?.attrInt) {
            	case 0x0006:
                	updateAttributeTextValue(descMap)
                	updateDataValue("datecode",descMap.value ?: "unknown")
                	break
                case 0x4000:
                	updateAttributeTextValue(descMap)
                	updateDataValue("softwareBuild",descMap.value ?: "unknown")
                    break
            }
        }
               
        if (descMap?.clusterInt == zigbee.POWER_CONFIGURATION_CLUSTER && descMap.commandInt != 0x07 && descMap.value) {
            // Multiple attributes can be encoded in descMap, pull them apart
            List<Map> descMaps = collectAttributes(descMap)

            def battMap = descMaps.find { it.attrInt == BATTERY_VOLTAGE_ATTR }  // Attribute 0x0020
            if (battMap) {
                eventList += getBatteryResult(Integer.parseInt(battMap.value, 16))
            }

            battMap = descMaps.find { it.attrInt == BATTERY_PERCENT_ATTR }  // Attribute 0x0021
            if (battMap) {
                eventList += getBatteryPercentageResult(Integer.parseInt(battMap.value, 16))
            }

        } else if (descMap?.clusterInt == zigbee.IAS_ZONE_CLUSTER && descMap.attrInt == zigbee.ATTRIBUTE_IAS_ZONE_STATUS && descMap?.value) {
            eventList += translateZoneStatus(new ZoneStatus(zigbee.convertToInt(descMap?.value, 16)))
        }
    }
    
    if (!eventList.isEmpty()) { 
        eventList += [ name: "lastCheckin", value: now, displayed: false ]
    }

    // For each item in the event list, create an actual event and append it to the result list.
    eventList.each {
        def event = createEvent(it)
        log.info "${event.descriptionText}"
        result += event
    }

    if (description?.startsWith('enroll request')) {
        List cmds = zigbee.enrollResponse()
        logDebug "enroll response: ${cmds}"
        result += cmds?.collect { new physicalgraph.device.HubAction(it) }
    }

    logDebug "parse() returning ${result}"
    return result
}

private Map parseIasMessage(String description) {
        ZoneStatus zs = zigbee.parseZoneStatus(description)

        translateZoneStatus(zs)
}

private Map translateZoneStatus(ZoneStatus zs) {
        return zs.isAlarm1Set() ? getMoistureResult('wet') : getMoistureResult('dry')
}

private Map getBatteryResult(rawValue) {
    // Passed as units of 100mv (27 = 2700mv = 2.7V)
    def rawVolts = String.format("%2.1f", rawValue / 10.0)

    def result = [:]
    result.name          = "batteryVoltage"
    result.value         = "${rawVolts}"
    result.unit          = 'V'
    result.displayed     = true
    result.isStateChange = true

    return result
}

private Map getBatteryPercentageResult(rawValue) {
    def result = [:]

    if (0 <= rawValue && rawValue <= 200) {
        result.name = 'battery'
        result.translatable = true
        result.value = Math.round(rawValue / 2)
        result.unit = '%'
        result.isStateChange = true
    }

    return result
}

private Map getMoistureResult(value) {
    def descriptionText
    if (value == "wet")
        descriptionText = '{{ device.displayName }} is wet'
    else
        descriptionText = '{{ device.displayName }} is dry'
    return [
        name           : 'water',
        value          : value,
        descriptionText: descriptionText,
        translatable   : true
    ]
}

/**
 * PING is used by Device-Watch in attempt to reach the Device
 * */
def ping() {
    logDebug "ping()"
}

def refresh() {
    logDebug "refresh() - Refreshing Values"
    def refreshCmds = []

    refreshCmds += zigbee.readAttribute(zigbee.POWER_CONFIGURATION_CLUSTER, BATTERY_PERCENT_ATTR)
    refreshCmds += zigbee.readAttribute(zigbee.POWER_CONFIGURATION_CLUSTER, BATTERY_VOLTAGE_ATTR)

    refreshCmds += 
        zigbee.readAttribute(zigbee.IAS_ZONE_CLUSTER, zigbee.ATTRIBUTE_IAS_ZONE_STATUS) +
        zigbee.enrollResponse()

    return refreshCmds
}

def configure() {
    log.debug "configure()"
    // Device-Watch allows 2 check-in misses from device + ping (plus 1 min lag time)
    def secondsBetweenCheckins = 3600
    sendEvent(name: "checkInterval", value: 2 * secondsBetweenCheckins + 1 * 60, displayed: false, data: [protocol: "zigbee", hubHardwareId: device.hub.hardwareID, offlinePingable: "1"])

	def cmds = []
    
    // Query some of the basic values
    cmds += zigbee.readAttribute(zigbee.BASIC_CLUSTER, 0x0006) // date code
    cmds += zigbee.readAttribute(zigbee.BASIC_CLUSTER, 0x4000) // SWbuild
    
    // Schedule battery reports for once an hour, or .1 volt or 1% loss
    cmds += zigbee.configureReporting(zigbee.POWER_CONFIGURATION_CLUSTER, BATTERY_VOLTAGE_ATTR, DataType.UINT8, 60, 3600, 1)   // Configure Battery Voltage - Report once per Xhrs or if a change of 1% detected
    cmds += zigbee.configureReporting(zigbee.POWER_CONFIGURATION_CLUSTER, BATTERY_PERCENT_ATTR, DataType.UINT8, 60, 3600, 1)   // Configure Battery Percentage - Report once per Xhrs or if a change of 1% detected
        
    return cmds + refresh() // send refresh cmds as part of config
}

def formatDate(batteryReset) {
    def correctedTimezone = ""
    def timeString = clockformat ? "HH:mm:ss" : "h:mm:ss aa"

    // If user's hub timezone is not set, display error messages in log and events log, and set timezone to GMT to avoid errors
    if (!(location.timeZone)) {
        correctedTimezone = TimeZone.getTimeZone("GMT")
        log.error "${device.displayName}: Time Zone not set, so GMT was used. Please set up your location in the SmartThings mobile app."
        sendEvent(name: "error", value: "", descriptionText: "ERROR: Time Zone not set, so GMT was used. Please set up your location in the SmartThings mobile app.")
    } else {
        correctedTimezone = location.timeZone
    }

    if (dateformat == "US" || dateformat == "" || dateformat == null) {
        if (batteryReset)
        return new Date().format("MMM dd yyyy", correctedTimezone)
        else
            return new Date().format("EEE MMM dd yyyy ${timeString}", correctedTimezone)
    } else if (dateformat == "UK") {
        if (batteryReset)
        return new Date().format("dd MMM yyyy", correctedTimezone)
        else
            return new Date().format("EEE dd MMM yyyy ${timeString}", correctedTimezone)
    } else {
        if (batteryReset)
        return new Date().format("yyyy MMM dd", correctedTimezone)
        else
            return new Date().format("EEE yyyy MMM dd ${timeString}", correctedTimezone)
    }
}

// Replace an encoded text value in a map with actual text
private updateAttributeTextValue(map) {
    if (map?.encoding == "42") {  // text 
        def valueString = parseAttributeText(map?.value)
        map.value = valueString
    }
}

private String parseAttributeText(value) {
	String ret = ""
    
    // Parsing text
    for (int i = 0; i < value.length(); i+=2) {
        def str = value.substring(i, i+2);
        def NextChar = (char)Integer.parseInt(str, 16);
        ret = ret + NextChar
    }
    
    return ret
}

private logDebug(msg) {
	if (settings?.debugOutput || settings?.debugOutput == null) {
		log.debug "$msg"
	}
}