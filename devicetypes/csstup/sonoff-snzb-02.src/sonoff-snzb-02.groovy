/*
 *  SONOFF SNZB-02 ZigBee Temperature & Humidity Sensor 
 *
 *  CoreyStup@gmail.com
 *
 *  V0.9: Original version forked from Matvei's version.  Added debug logging and setting parameters for checkin frequency.
 *
 *  
 * Originally taken courtesy of Matvei Vevitsis, from:
 * 7/3/21: https://github.com/Matvei27/sonoff/blob/main/sonoff-temp-humidity-sensor.groovy
 * 
 *  Copyright 2021 Matvei Vevitsis
 *  Based on code copyright SmartThings
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 */
import physicalgraph.zigbee.zcl.DataType

metadata {
    definition(name: "SONOFF SNZB-02", namespace: "csstup", author: "coreystup@gmail.com",  mnmn: "SmartThingsCommunity", 
               ocfDeviceType: "oic.d.thermostat",
               vid: "e89a61ab-bde3-39eb-b9d6-c3b34b25120e") {
        //use vid: "e89a61ab-bde3-39eb-b9d6-c3b34b25120e" to enable refresh button
        //use vid: "23c9be50-98c3-34cb-b52f-ecc9fbfe72cc" to disable refresh button
        capability "Configuration"
        capability "Battery"
        capability "Refresh"
        capability "Temperature Measurement"
        capability "Relative Humidity Measurement"
        capability "Sensor"
        capability "Health Check"
        
        attribute "batteryVoltage", "String"
        
        // raw fingerprint: 01 0104 0302 00 05 0000 0003 0402 0405 0001 01 0003
        fingerprint profileId: "0104", inClusters: "0000,0003,0402,0405", outClusters: "0003", model: "TH01", deviceJoinName: "SONOFF Temperature & Humidity Sensor", manufacturer: "eWeLink"
        fingerprint profileId: "0104", inClusters: "0000, 0001, 0003, 0020, 0402, 0405, FC57",  outClusters: "0003, 0019", manufacturer: "eWeLink", model: "SNZB-02P", deviceJoinName: "SONOFF Temperature & Humidity Sensor"
    }

    preferences {
        input "tempOffset", "number", title: "Temperature offset", description: "Select how many degrees to adjust the temperature.", range: "*..*", displayDuringSetup: false
        input "humidityOffset", "number", title: "Humidity offset", description: "Enter a percentage to adjust the humidity.", range: "*..*", displayDuringSetup: false
       	input name: "debugEnable", type: "bool", title: "Enable Debug Logging?", required: true, defaultValue: false
       	input name: "infoEnable", type: "bool", title: "Enable Informational Logging?", required: true, defaultValue: true       
	}

}

def parse(String description) {
    logDebug "parse() description: $description"
    
    def result = [] // Create an empty result list

	if (state.refreshPending == true)
    { 
    	logDebug "refresh is pending.  Requesting reporting changes."
        state.refreshPending = false
        
        return getReportingCmds()
    }

    // getEvent will handle temperature and humidity
    Map map = zigbee.getEvent(description)
    if (!map) {
        Map descMap = zigbee.parseDescriptionAsMap(description)
        logDebug "Parsed Description : $descMap"
		if (descMap?.clusterInt == zigbee.TEMPERATURE_MEASUREMENT_CLUSTER && descMap.commandInt == 0x07) {
            if (descMap.data[0] == "00") {
                logDebug "TEMP REPORTING CONFIG RESPONSE: $descMap"
			} else {
                log.warn "TEMP REPORTING CONFIG FAILED- error code: ${descMap.data[0]}"
            }
        } else if (descMap?.clusterInt == zigbee.RELATIVE_HUMIDITY_CLUSTER && descMap.commandInt == 0x07) {
            if (descMap.data[0] == "00") {
                logDebug "HUMIDITY REPORTING CONFIG RESPONSE: $descMap"
			} else {
                log.warn "HUMIDITY REPORTING CONFIG FAILED- error code: ${descMap.data[0]}"
            }    
        } else if (descMap.clusterInt == 0x0001 && descMap.commandInt != 0x07 && descMap?.value) {
			if (descMap.attrInt == 0x0021) {
            	map = getBatteryPercentageResult(Integer.parseInt(descMap.value, 16))
			} else if (descMap.attrInt == 0x0020) {
				map = getBatteryResult(Integer.parseInt(descMap.value, 16))  // Parse voltage attribute
            }
		}
    } else if (map.name == "temperature") { 
        map.value = (float) Math.round( (map.value as Float) * 10.0 ) / 10
        if (tempOffset) {
            map.value = (float) map.value + (float) tempOffset
        }
        if (map?.unit) {
             map.unit = "°${map.unit}"
        }
    } else if (map.name == "humidity") {
        if (humidityOffset) {
            map.value = (int) map.value + (int) humidityOffset
        }   	
    }
    
    logDebug "Parse returned $map"
    
    if (map) {
    	result += createEvent(map)
    } 
   
    logDebug "parse() returning result: ${result}"
    
    return result
}

// installed() runs just after a sensor is paired using the "Add a Thing" method in the SmartThings mobile app
def installed() {
	logDebug "Device installed..."
    configure()
}

// configure() runs after installed() when a sensor is paired
def configure() {
    logDebug "...configuring reporting settings..."
	
    // Device-Watch allows 2 check-in misses from device + ping (plus 1 min lag time)
	sendEvent(name: "checkInterval", value: 2 * 60 * 60 + 1 * 60, displayed: false, data: [protocol: "zigbee", hubHardwareId: device.hub.hardwareID])
	
    return getReportingCmds()
}

List getReportingCmds() {
	// Configure reporting
    def min = 60             // In seconds.  Min 60 seconds (never more frequent than this)
    def sensor_max = 300     // In seconds   Max 300 seconds (5 minutes, at least as frequent as this)
    def battery_max = 3600   // In seconds   Max 3600 seconds (24 hours, at least as frequent as this)
    
    return zigbee.temperatureConfig(min, sensor_max, 50)  + // Min 60 seconds, max 300 seconds, or 10 = 0.1C, 50 = 0.5C (1F)
		   zigbee.configureReporting(zigbee.RELATIVE_HUMIDITY_CLUSTER, 0x0000, DataType.UINT16, min, sensor_max, 100) +      //humidity default reportableChange 100 = 1%
           zigbee.configureReporting(zigbee.POWER_CONFIGURATION_CLUSTER, 0x0020, DataType.UINT8, min, battery_max, 0x01) +   //default reportableChange 1 = 0.1v
           zigbee.configureReporting(zigbee.POWER_CONFIGURATION_CLUSTER, 0x0021, DataType.UINT8, min, battery_max, 0x02)     //default reportableChange 2 = 1%
}

def updated() {
	logDebug "updated()"
	configure()
}

def ping() {
	logDebug "ping()"
    return refresh()
}

def refresh() {
    logDebug "Device refresh requested..."
    state.refreshPending = true
    return zigbee.readAttribute(zigbee.TEMPERATURE_MEASUREMENT_CLUSTER, 0x0000) +
           zigbee.readAttribute(zigbee.RELATIVE_HUMIDITY_CLUSTER, 0x0000) +
		   zigbee.readAttribute(zigbee.POWER_CONFIGURATION_CLUSTER, 0x0020) +  // battery voltage (0x0020)
           zigbee.readAttribute(zigbee.POWER_CONFIGURATION_CLUSTER, 0x0021)    // battery percentage (0x0021)

}

def getBatteryPercentageResult(rawValue) {
	logDebug "Battery Percentage rawValue = ${rawValue} -> ${rawValue / 2}%"
	def result = [:]

	if (0 <= rawValue && rawValue <= 200) {
		result.name = 'battery'
		result.value = Math.round(rawValue / 2)
        result.unit = "%"
	}

	return result
}

private Map getBatteryResult(rawValue) {
	def volts = rawValue / 10
    
	logDebug "Battery voltage rawValue = ${rawValue} -> ${volts}V"

    sendEvent(name: "batteryVoltage", value: volts, unit: "V", displayed: false, isStateChange:true)
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
	// log.trace "$msg"
}
