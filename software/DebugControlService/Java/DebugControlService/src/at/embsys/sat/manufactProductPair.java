package at.embsys.sat;

/**
 * Created by mazi on 13.11.2018.
 */
public class ManufactProductPair {
	String manufacturerID, productID;


	public ManufactProductPair(String manufacturerID, String productID) {
		this.manufacturerID = manufacturerID;
		this.productID = productID;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;

		ManufactProductPair that = (ManufactProductPair) o;

		if (!manufacturerID.equals(that.manufacturerID)) return false;
		return productID.equals(that.productID);

	}

	@Override
	public int hashCode() {
		int result = manufacturerID.hashCode();
		result = 31 * result + productID.hashCode();
		return result;
	}
}
