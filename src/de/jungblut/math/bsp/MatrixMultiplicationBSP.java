package de.jungblut.math.bsp;

import java.io.IOException;
import java.util.Map.Entry;
import java.util.TreeMap;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hama.HamaConfiguration;
import org.apache.hama.bsp.BSP;
import org.apache.hama.bsp.BSPJob;
import org.apache.hama.bsp.BSPPeer;
import org.apache.hama.bsp.Partitioner;
import org.apache.hama.bsp.SequenceFileInputFormat;
import org.apache.hama.bsp.sync.SyncException;

import de.jungblut.clustering.model.VectorWritable;
import de.jungblut.math.dense.DenseDoubleMatrix;
import de.jungblut.math.dense.DenseDoubleVector;
import de.jungblut.math.sparse.SparseDoubleVector;

public final class MatrixMultiplicationBSP extends
    BSP<IntWritable, VectorWritable, IntWritable, VectorWritable> {

  private static final String HAMA_MAT_MULT_B_PATH = "hama.mat.mult.B.path";

  private SequenceFile.Reader reader;

  @Override
  public void setup(
      BSPPeer<IntWritable, VectorWritable, IntWritable, VectorWritable> peer)
      throws IOException, SyncException, InterruptedException {
    Configuration conf = peer.getConfiguration();
    reopenOtherMatrix(conf);
  }

  @Override
  public void bsp(
      BSPPeer<IntWritable, VectorWritable, IntWritable, VectorWritable> peer)
      throws IOException, SyncException, InterruptedException {

    // properties for our output matrix C
    int otherMatrixColumnDimension = -1;
    boolean otherVectorSparse = false;

    IntWritable rowKey = new IntWritable();
    VectorWritable value = new VectorWritable();
    while (peer.readNext(rowKey, value)) {
      // System.out.println(peer.getPeerName() + " " + rowKey.get() + "|"
      // + value.toString());
      IntWritable bMatrixKey = new IntWritable();
      VectorWritable columnVector = new VectorWritable();
      while (reader.next(bMatrixKey, columnVector)) {
        // detect the properties of our columnvector
        if (otherMatrixColumnDimension == -1) {
          otherMatrixColumnDimension = columnVector.getVector().getDimension();
          otherVectorSparse = columnVector.getVector().isSparse();
        }
        double dot = value.getVector().dot(columnVector.getVector());
        // we use row based partitioning once again to distribute the outcome
        peer.send(peer.getPeerName(rowKey.get() & (peer.getNumPeers() - 1)),
            new ResultMessage(rowKey.get(), bMatrixKey.get(), dot));
      }
      reopenOtherMatrix(peer.getConfiguration());
    }

    peer.sync();

    // a peer gets all column entries for multiple rows based on row number
    // TODO order is not preserved, so it must use a bit more memory..
    // TODO time to add a sorting message manager?
    TreeMap<Integer, VectorWritable> rowMap = new TreeMap<Integer, VectorWritable>();
    ResultMessage currentMessage = null;
    while ((currentMessage = (ResultMessage) peer.getCurrentMessage()) != null) {
//      System.out.println(peer.getPeerName() + " "
//          + currentMessage.getTargetRow() + "x"
//          + currentMessage.getTargetColumn() + " = "
//          + currentMessage.getValue());
      VectorWritable vectorWritable = rowMap.get(currentMessage.getTargetRow());
      if (vectorWritable == null) {
        VectorWritable v = new VectorWritable(
            otherVectorSparse ? new SparseDoubleVector(
                otherMatrixColumnDimension) : new DenseDoubleVector(
                otherMatrixColumnDimension));
        rowMap.put(currentMessage.getTargetRow(), v);
        vectorWritable = v;
      }
      vectorWritable.getVector().set(currentMessage.getTargetColumn(),
          currentMessage.getValue());
    }

    // write all the rows out..

    for (Entry<Integer, VectorWritable> entry : rowMap.entrySet()) {
      peer.write(new IntWritable(entry.getKey()), entry.getValue());
    }
  }

  public void reopenOtherMatrix(Configuration conf) throws IOException {
    if (reader != null) {
      reader.close();
    }
    reader = new SequenceFile.Reader(FileSystem.get(conf), new Path(
        conf.get(HAMA_MAT_MULT_B_PATH)), conf);
  }

  public static void main(String[] args) throws IOException,
      InterruptedException, ClassNotFoundException {

    Configuration conf = new Configuration();
    conf.set("bsp.local.tasks.maximum", "2");

    DenseDoubleMatrix a = new DenseDoubleMatrix(new double[][] { { 8, 9 },
        { 5, -1 } });
    DenseDoubleMatrix b = new DenseDoubleMatrix(new double[][] { { -2, 3 },
        { 4, 0 } });

    Path inPath = new Path("files/matrixmult/in/A.seq");
    writeSequenceFileMatrix(conf, a, inPath, false);
    Path bPath = new Path("files/matrixmult/in/B.seq");
    // store this in column major format
    writeSequenceFileMatrix(conf, b, bPath, true);

    conf.set(HAMA_MAT_MULT_B_PATH, bPath.toString());
    Path outPath = new Path("files/matrixmult/out/");

    BSPJob job = new BSPJob(new HamaConfiguration(conf));
    job.setInputFormat(SequenceFileInputFormat.class);
    job.setInputPath(inPath);
    job.setOutputKeyClass(IntWritable.class);
    job.setOutputValueClass(VectorWritable.class);
    job.setOutputPath(outPath);
    job.setBspClass(MatrixMultiplicationBSP.class);
    job.setPartitioner(MatrixRowPartitioner.class);
    job.waitForCompletion(true);

    /*
     * Answer: [20.0, 24.0] [-14.0, 15.0]
     */
  }

  private static void writeSequenceFileMatrix(Configuration conf,
      DenseDoubleMatrix denseDoubleMatrix, Path p, boolean columnMajor) {
    SequenceFile.Writer writer = null;
    try {
      FileSystem fs = FileSystem.get(conf);
      writer = new SequenceFile.Writer(fs, conf, p, IntWritable.class,
          VectorWritable.class);
      if (!columnMajor) {
        for (int i = 0; i < denseDoubleMatrix.getRowCount(); i++) {
          VectorWritable vectorWritable = new VectorWritable(
              denseDoubleMatrix.getRowVector(i));
          writer.append(new IntWritable(i), vectorWritable);
        }
      } else {
        for (int i = 0; i < denseDoubleMatrix.getColumnCount(); i++) {
          VectorWritable vectorWritable = new VectorWritable(
              denseDoubleMatrix.getColumnVector(i));
          writer.append(new IntWritable(i), vectorWritable);
        }
      }

    } catch (IOException e) {
      e.printStackTrace();
    } finally {
      if (writer != null) {
        try {
          writer.close();
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    }
  }

  private static final class MatrixRowPartitioner implements
      Partitioner<IntWritable, VectorWritable> {

    @Override
    public final int getPartition(IntWritable key, VectorWritable value,
        int numTasks) {
      return key.get() & (numTasks - 1);
    }
  }

}