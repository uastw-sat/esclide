package at.embsys.sat;

import javafx.application.Platform;
import javafx.scene.control.ListView;
import org.slf4j.LoggerFactory;

import javax.usb.*;
import javax.usb.event.*;
import java.util.ArrayList;

public class USBHotPlugListener implements UsbServicesListener {
	private ch.qos.logback.classic.Logger logger;
	private ListView<String> listView;

	public USBHotPlugListener(ListView listView) {
		/* Instantiate logger */
		logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(USBHotPlugListener.class);
		/* Set the log level */
		logger.setLevel(Main.logLevel);
		this.listView = listView;
	}

	public void usbDeviceAttached(UsbServicesEvent event) {
		Main.enumUsbVbox.setVisible(true);
		UsbDevice device = event.getUsbDevice();
		ArrayList<String> alreadyFoundSNs = new ArrayList<>();
		for (int i = 0; i < listView.getItems().size(); i++) {
			alreadyFoundSNs.add(listView.getItems().get(i).substring(listView.getItems().get(i).indexOf("#")+1,listView.getItems().get(i).indexOf("]")));
		}
		DeviceInfo deviceInfo = new DeviceInfo(device, false, alreadyFoundSNs);
		String deviceInfoString = getDeviceInfo(deviceInfo);
		logger.info(deviceInfoString + " was added to the bus.");
		if (getDeviceInfo(deviceInfo) != null && !getDeviceInfo(deviceInfo).isEmpty()) {
			//JavaFX UI must be updated from application thread, but here we are in the "Device Scanner thread", so do UI operations later.
			Platform.runLater(() -> {
				listView.getItems().add(getDeviceInfo(deviceInfo));
			});
		}
		Main.enumUsbVbox.setVisible(false);
	}



	public void usbDeviceDetached(UsbServicesEvent event) {
		Main.enumUsbVbox.setVisible(true);
		UsbDevice device = event.getUsbDevice();
		ArrayList<String> alreadyFoundSNs = new ArrayList<>();
		alreadyFoundSNs.addAll(listView.getItems());
		DeviceInfo deviceInfo = new DeviceInfo(device, true, alreadyFoundSNs);
		if (deviceInfo.getSerial() != null)
			logger.info(getDeviceInfo(deviceInfo) + " was removed from the bus.");
		else
			logger.info("Device [" + deviceInfo.getManufacturer() + "/" + deviceInfo.getProduct() + "/" + deviceInfo.getBus() + "/" + deviceInfo.getDeviceNumber() + "] was removed from the bus.");
		//Search the removed device and remove it from listView
		for (int i = 0; i < listView.getItems().size(); i++) {
			if (listView.getItems().get(i).contains("Device [" + deviceInfo.getManufacturer() + "/" + deviceInfo.getProduct() + "/" + deviceInfo.getBus() + "/" + deviceInfo.getDeviceNumber())) {
				final int removeIndex = i;
				//JavaFX UI must be updated from application thread, but here we are in the "Device Scanner thread", so do UI operations later.
				//Also: variables in lamda expressions must be final.
				Platform.runLater(() -> {
					listView.getItems().remove(removeIndex);
				});

			}
		}
		Main.enumUsbVbox.setVisible(false);
	}

	private static String getDeviceInfo(DeviceInfo deviceInfo) {
		String ret = "";
		if (deviceInfo.getSerial() != null) {
			ret = "Device [" + deviceInfo.getManufacturer() + "/" + deviceInfo.getProduct() + "/" + deviceInfo.getBus() + "/" + deviceInfo.getDeviceNumber() + "/#" + deviceInfo.getSerial() + "] ";
			if ((deviceInfo.getManufacturerName() != null) && (deviceInfo.getProductName() != null))
				ret += "(Manufact.: " + deviceInfo.getManufacturerName() + ", Prod.: " + deviceInfo.getProductName() + ")";
			else if ((deviceInfo.getManufacturerName() == null) && (deviceInfo.getProductName() != null))
				ret += "(Prod.: " + deviceInfo.getProductName() + ")";
			else
				ret += "(unknown descriptor - Installing a libusbK driver for this device might help to read descriptors.)";
		}
		return ret;
	}
}