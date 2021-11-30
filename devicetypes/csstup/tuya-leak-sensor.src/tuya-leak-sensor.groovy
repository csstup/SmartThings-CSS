/*
 * Tuya Leak Sensor
 * 
 * Sold as:
 * "Tuya Water Sensor" (TS0207)
 * Blitzwolf BW-IS5 Water Leak Sensor https://www.blitzwolf.com/ZigBee-Water-Leak-Sensor-p-444.html
 * RSH-ZigBee-WS01
 * Ewelink Wireless Water Detector
 * And probably others...
 *
 * To pair this device, press and hold the button for 5+ seconds.  
 * While holding, the green light will go out indicating its ready to pair.
 * Release the button, the green light should begin to blink indicating its ready to pair.
 *
 * Pressing the button will send the current leak status as a zone status update.
 *
 * Every ~250 minutes or so, the device will send a battery report (with voltage and percentage remaining),
 * which we consider to be a checkin.  The frequency of the battery reports is not queryable or configurable.
 * 
 * Details:
 * Leak sensor only.  No temperature or humidity. 
 * Uses a single 2032 battery
 * Does not do long poll checkins on any frequency
 * Water events:
 *   Registers as an IAS device.    Water events are sent as an IAS zone status update for alarm 1.
 *
 * Update history:
 * 11/29/2021 - V0.9   - Initial version
 * 11/30/2021 - V0.9.1 - Added fingerprint for BlitzWolf BW-IS5
 * 
 *
 * Get updates from:
 * https://github.com/csstup/SmartThings-CSS/blob/master/devicetypes/csstup/tuya-leak-sensor.src/tuya-leak-sensor.groovy 
 *
 * Original DTH code/concepts taken from:
 *  SmartSense Moisture Sensor
 *
 */
import physicalgraph.zigbee.clusters.iaszone.ZoneStatus
import physicalgraph.zigbee.zcl.DataType

metadata {
        definition(name: "Tuya Leak Sensor", namespace: "csstup", author: "coreystup@gmail.com", 
               runLocally: false, minHubCoreVersion: '000.017.0012', executeCommandsLocally: false, 
               mnmn: "SmartThings", vid: "generic-leak", genericHandler: "Zigbee") {
                capability "Configuration"
                capability "Battery"
                capability "Refresh"
                capability "Water Sensor"
                capability "Health Check"
                capability "Sensor"
        
                attribute "lastCheckin", "String"
                attribute "batteryVoltage", "String"

                fingerprint endpointId: "01", profileId:"0104", inClusters: "0000,0001,0003,0500,EF01", outClusters: "0003,0019", model: "TS0207", manufacturer: "_TYZB01_sqmd19i1", deviceJoinName: "Tuya Leak Sensor"       // TS0207
                fingerprint endpointId: "01", profileId:"0104", inClusters: "0000,0001,0003,0500,EF01", outClusters: "0003,0019", model: "TS0207", manufacturer: "_TYZB01_o63ssaah", deviceJoinName: "Blitzwolf Leak Sensor"  // BlitzWolf BW-IS5

        }

        simulator {
        }

        preferences {
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
        log.debug "parse() description: $description"
       
        // Determine current time and date in the user-selected date format and clock style
        def now = formatDate()    

        def result = []  // Create an empty result list
        def eventList = []  // A list of events, to be processed by createEvent
    
        if (description?.startsWith('zone status')) {
                eventList += parseIasMessage(description)
        } else {
                // parseDescriptionAsMap() can return additional attributes into the additionalAttrs map member
                Map descMap = zigbee.parseDescriptionAsMap(description)

                if (descMap?.clusterInt == zigbee.POWER_CONFIGURATION_CLUSTER && descMap.commandInt != 0x07 && descMap.value) {
                        // Multiple attributes are encoded in descMap, pull them apart
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
                result += createEvent(it)
        }

        if (description?.startsWith('enroll request')) {
                List cmds = zigbee.enrollResponse()
                log.debug "enroll response: ${cmds}"
                result += cmds?.collect { new physicalgraph.device.HubAction(it) }
        }
    
        log.debug "parse() returning ${result}"
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
        log.debug "ping()"
}

def refresh() {
        log.debug "refresh() - Refreshing Values"
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
        // This device checks in every 250 minutes or so (usually 247-248 minutes) with a battery update.
        def secondsBetweenCheckins = 250 * 60
        sendEvent(name: "checkInterval", value: 2 * secondsBetweenCheckins, displayed: false, data: [protocol: "zigbee", hubHardwareId: device.hub.hardwareID, offlinePingable: "1"])
        
        return refresh() // send refresh cmds as part of config
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
