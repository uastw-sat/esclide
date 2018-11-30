package at.embsys.sat;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.slf4j.LoggerFactory;

import javax.usb.UsbDevice;
import java.util.ArrayList;

import com.sun.jna.Library;
import com.sun.jna.Native;

public class DeviceInfo {
	private ch.qos.logback.classic.Logger logger;
	private String serial;
	private String manufacturerName;
	private String manufacturer;
	private String productName;
	private String product;
	private String bus;
	private String deviceNumber;

	public interface JLinkDll extends Library {
		JLinkDll INSTANCE = (JLinkDll) Native.loadLibrary(
				(SystemUtils.IS_OS_WINDOWS ? "JLink_x64" : ""), JLinkDll.class);
		int JLINKARM_GetSN();
	}


	public DeviceInfo(UsbDevice device, boolean dontUseFallbacks, ArrayList<String> alreadyFoundSNs) {
		/* Instantiate logger */
		logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(DeviceInfo.class);
		  /* Set the log level */
		logger.setLevel(Main.logLevel);

		String tmp0 = device.toString().substring(device.toString().indexOf("Bus "));
		if (!tmp0.isEmpty()) {
			String[] tmp1 = tmp0.split("[: ]");
			bus = tmp1[1];
			deviceNumber = tmp1[3];
			manufacturer = tmp1[6];
			product = tmp1[7];
		}

		if ((Main.driverTypes.get(new ManufactProductPair(manufacturer, product)) instanceof DriverType.JLink) || (Main.driverTypes.get(new ManufactProductPair(manufacturer, product)) instanceof DriverType.OpenOCD)) {

			try {
				//this does not work when detached (throws exception)
				serial = device.getSerialNumberString();
				manufacturerName = device.getManufacturerString();
				productName = device.getProductString();
			} catch (Exception e) { // | ClassCastException | UsbException | UnsupportedEncodingException e) {
				//throw new Exception("xxx",null,true,true);
				logger.warn("Can't get device info (descriptor) on " + device.toString() + ". Installing a libusbK driver for this device might help to read descriptor. Trying to retrieve serial number by fallbacks...");
			}

			//Use fallback if libusb was not able to read SN
			if (!dontUseFallbacks && (serial == null || serial.isEmpty())) {

				//Fallback 1: use JLink DLL (ONLY for JLink boards e.g. Infinion XMC)
				if (Main.driverTypes.get(new ManufactProductPair(manufacturer, product)) instanceof DriverType.JLink) {
					JLinkDll jLinkDll = JLinkDll.INSTANCE;
					serial = String.valueOf(jLinkDll.JLINKARM_GetSN());
					if (serial != null) {
						serial = StringUtils.leftPad(serial, 12, "0");
						productName = "J-Link driver";
					}
					logger.info("Fallback1 (JLink DLL) yielded SN: " + serial);

					//Be careful with the output of the DLL
					if (serial != null && alreadyFoundSNs.size() > 0) {
						if (alreadyFoundSNs.contains(serial)) {
							serial = null;
							logger.info("SN was already listed, using fallback2...");
						}
					}
					if (serial != null) {
						if (serial.contains("0000000000")) {
							serial = null;
							logger.info("SN invalid, using fallback2...");
						}
					}

				}

				//Fallback 2: use wmic,lsusb,...
				if (serial == null || serial.isEmpty()) {
					DistinctPlatformDetector distinctPlatformDetector = new DistinctPlatformDetector(manufacturer, product, alreadyFoundSNs);
					serial = distinctPlatformDetector.getSerial();
					productName = distinctPlatformDetector.getProductName();
					logger.info("Fallback2 (wmic/lsusb) yielded SN: " + serial);
				}
			}
		}
	}

	public String getSerial() {
		return serial;
	}

	public String getManufacturerName() {
		return manufacturerName;
	}

	public String getManufacturer() {
		return manufacturer;
	}

	public String getProductName() {
		return productName;
	}

	public String getProduct() {
		return product;
	}

	public String getBus() {
		return bus;
	}

	public String getDeviceNumber() {
		return deviceNumber;
	}

	public String toString() {
		return "Device [" + manufacturer + "/" + product + "/" + bus + "/" + deviceNumber + "/#" + serial + "]";
	}
}
