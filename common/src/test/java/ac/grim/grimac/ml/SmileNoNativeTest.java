package ac.grim.grimac.ml;

import org.junit.jupiter.api.Test;
import smile.data.DataFrame;
import smile.data.Tuple;
import smile.data.formula.Formula;
import smile.data.type.DataTypes;
import smile.data.type.StructField;
import smile.data.type.StructType;
import smile.data.vector.DoubleVector;
import smile.regression.GradientTreeBoost;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SmileNoNativeTest {

    @Test
    void gradientTreeBoostWorksWithoutNativeLibs() {
        double[][] x = {{1, 2}, {2, 3}, {3, 4}, {4, 5}};
        double[] y = {1, 2, 3, 4};
        DataFrame frame = DataFrame.of(x, "a", "b").merge(DoubleVector.of("target", y));
        GradientTreeBoost model = GradientTreeBoost.fit(Formula.lhs("target"), frame);
        StructType schema = new StructType(new StructField("a", DataTypes.DoubleType), new StructField("b", DataTypes.DoubleType));
        double pred = model.predict(Tuple.of(new double[] {2, 3}, schema));
        assertTrue(pred > 0);
    }
}
