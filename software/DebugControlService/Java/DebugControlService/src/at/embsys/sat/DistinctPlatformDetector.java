package at.embsys.sat;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.ListView;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;

public class DistinctPlatformDetector {
	private static Process procUSBDevices;
	private static final String OS = System.getProperty("os.name").toLowerCase();
	private static String processInput;
	private String serial;
	private String productName;

	/*
	Retrieve serial number of USB device in case it was not determined by the previous usb4java functions (hotplug). This may occur
	on Windows, if the J-LInk driver is installed for a e.g. infinion dev board. Usb4Java can't communicate with other drivers than libusbK or winusb.
	Get it by starting lsusb or wmic one time only with a given VID and PID of the USB device.
	 */

	public DistinctPlatformDetector(String vendorID, String productID, ArrayList<String> alreadyFoundSNs) {
	/* Instantiate logger */
		ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(DistinctPlatformDetector.class);
		  /* Set the log level */
		logger.setLevel(Main.logLevel);

	   /* Check OS and get available USB devices */
		if (OS.contains("linux")) {
			try {
				procUSBDevices = Runtime.getRuntime().exec("lsusb -v");
			} catch (IOException e) {
				logger.warn(e.getMessage());
			}
		} else if (OS.contains("mac")) {
			try {
				procUSBDevices = Runtime.getRuntime().exec("system_profiler SPUSBDataType");
			} catch (IOException e) {
				logger.warn(e.getMessage());
			}
		} else if (OS.contains("windows")) {
			try {
				//wmic path CIM_LogicalDevice where "Description like '%USB%'" get /value
				procUSBDevices = Runtime.getRuntime().exec("wmic path CIM_LogicalDevice where \"Description like '%J-Link%' or Description like '%In-Circuit Debug Interface%	'\" get /value");
			} catch (IOException e) {
				logger.warn(e.getMessage());
			}
		} else {
			logger.error("Operating System not supported!");
		}

            /* Create Buffered readers for OCDS process */
		BufferedReader stdInputInit = new BufferedReader(new
				InputStreamReader(procUSBDevices.getInputStream()));

		BufferedReader stdErrorInit = new BufferedReader(new
				InputStreamReader(procUSBDevices.getErrorStream()));

		try {

			serial = null;
			processInputLoop:
			while ((processInput = stdInputInit.readLine()) != null) {

				if (OS.contains("linux")) {
					if (processInput.contains("iSerial"))
						serial = processInput.split(" ")[processInput.split(" ").length - 1];
				} else if (OS.contains("mac")) {
					if (processInput.contains("Serial Number: "))
						serial = processInput.split(":")[processInput.split(":").length - 1].replace(" ", "");
				} else if (OS.contains("windows")) {
					if (processInput.contains("Description=J-Link driver")) {
						productName = processInput.split("=")[1];
					}
					//example line: DeviceID=USB\VID_1366&amp;PID_0101\000551011513
					if (processInput.contains("DeviceID=USB\\VID_" + vendorID + "&amp;PID_" + productID)) {
						serial = processInput.split("\\\\")[processInput.split("\\\\").length - 1];
					}
				}

				//Check if this serial was retrieved before, if so, continue b.c. maybe another board was connected (multiple boards)
				if (serial != null && alreadyFoundSNs.size() > 0) {
					if (alreadyFoundSNs.contains(serial)) {
						serial = null;
						continue processInputLoop;
					} else
						break processInputLoop;
				}
			}


			//for wmic: Description usually is listed before serial, so to make sure productName refers to specified vendor and product ID, clear it if serial was not read
			if (serial == null || serial.isEmpty())
				productName = null;

			/* read any errors from the attempted command */
			while ((processInput = stdErrorInit.readLine()) != null) {
				if (processInput != null)
					logger.debug(processInput);
			}

		} catch (IOException e) {
			logger.debug("An IOException was thrown", e);
		} finally {
		}


	}


	public String getSerial() {
		return serial;
	}

	public String getProductName() {
		return productName;
	}
}

