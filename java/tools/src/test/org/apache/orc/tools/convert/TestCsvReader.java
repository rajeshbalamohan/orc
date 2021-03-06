/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.orc.tools.convert;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.ql.exec.vector.BytesColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.DecimalColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.DoubleColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.ListColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.LongColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.MapColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.StructColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.TimestampColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.VectorizedRowBatch;
import org.apache.hadoop.hive.serde2.io.DateWritable;
import org.apache.hadoop.hive.serde2.io.HiveDecimalWritable;
import org.apache.hadoop.io.BytesWritable;
import org.apache.orc.ColumnStatistics;
import org.apache.orc.CompressionKind;
import org.apache.orc.OrcConf;
import org.apache.orc.OrcFile;
import org.apache.orc.Reader;
import org.apache.orc.RecordReader;
import org.apache.orc.StripeStatistics;
import org.apache.orc.TypeDescription;
import org.apache.orc.Writer;
import org.apache.orc.tools.FileDump;
import org.apache.orc.tools.TestJsonFileDump;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.PrintStream;
import java.io.StringReader;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

public class TestCsvReader {

  Configuration conf;

  @Before
  public void openFileSystem () throws Exception {
    conf = new Configuration();
  }

  @Test
  public void testSimple() throws Exception {
    StringReader input = new StringReader(
        "1,1.25,1.01,'a'\n" +
        "2,2.5,2.02,'14'\n" +
        "3,3.75,3.03,'1e'\n" +
        "4,5,4.04,'28'\n" +
        "5,6.25,5.05,'32'\n" +
        "6,7.5,6.06,'3c'\n" +
        "7,8.75,7.07,'46'\n" +
        "8,10,8.08,'50'\n"
    );
    TypeDescription schema = TypeDescription.fromString(
        "struct<a:int,b:double,c:decimal(10,2),d:string>");
    RecordReader reader = new CsvReader(input, null, 1, schema, ',', '\'',
        '\\', 0, "");
    VectorizedRowBatch batch = schema.createRowBatch(5);
    assertEquals(true, reader.nextBatch(batch));
    assertEquals(5, batch.size);
    for(int r = 0; r < batch.size; ++r) {
      assertEquals(r+1, ((LongColumnVector) batch.cols[0]).vector[r]);
      assertEquals(1.25 * (r + 1), ((DoubleColumnVector) batch.cols[1]).vector[r], 0.001);
      assertEquals((r + 1) + ".0" + (r + 1), ((DecimalColumnVector) batch.cols[2]).vector[r].toFormatString(2));
      assertEquals(Integer.toHexString((r + 1) * 10), ((BytesColumnVector) batch.cols[3]).toString(r));
    }
    assertEquals(true, reader.nextBatch(batch));
    assertEquals(3, batch.size);
    for(int r = 0; r < batch.size; ++r) {
      assertEquals(r + 6, ((LongColumnVector) batch.cols[0]).vector[r]);
      assertEquals(1.25 * (r + 6), ((DoubleColumnVector) batch.cols[1]).vector[r], 0.001);
      assertEquals((r + 6) + ".0" + (r + 6), ((DecimalColumnVector) batch.cols[2]).vector[r].toFormatString(2));
      assertEquals(Integer.toHexString((r + 6) * 10), ((BytesColumnVector) batch.cols[3]).toString(r));
    }
    assertEquals(false, reader.nextBatch(batch));
  }

  @Test
  public void testNulls() throws Exception {
    StringReader input = new StringReader(
        "1,1,1,'a'\n" +
        "'null','null','null','null'\n" +
        "3,3,3,'row 3'\n"
    );
    TypeDescription schema = TypeDescription.fromString(
        "struct<a:int,b:double,c:decimal(10,2),d:string>");
    RecordReader reader = new CsvReader(input, null, 1, schema, ',', '\'',
        '\\', 0, "null");
    VectorizedRowBatch batch = schema.createRowBatch();
    assertEquals(true, reader.nextBatch(batch));
    assertEquals(3, batch.size);
    for(int c=0; c < 4; ++c) {
      assertEquals("column " + c, false, batch.cols[c].noNulls);
    }

    // check row 0
    assertEquals(1, ((LongColumnVector) batch.cols[0]).vector[0]);
    assertEquals(1, ((DoubleColumnVector) batch.cols[1]).vector[0], 0.001);
    assertEquals("1", ((DecimalColumnVector) batch.cols[2]).vector[0].toString());
    assertEquals("a", ((BytesColumnVector) batch.cols[3]).toString(0));
    for(int c=0; c < 4; ++c) {
      assertEquals("column " + c, false, batch.cols[c].isNull[0]);
    }

    // row 1
    for(int c=0; c < 4; ++c) {
      assertEquals("column " + c, true, batch.cols[c].isNull[1]);
    }

    // check row 2
    assertEquals(3, ((LongColumnVector) batch.cols[0]).vector[2]);
    assertEquals(3, ((DoubleColumnVector) batch.cols[1]).vector[2], 0.001);
    assertEquals("3", ((DecimalColumnVector) batch.cols[2]).vector[2].toString());
    assertEquals("row 3", ((BytesColumnVector) batch.cols[3]).toString(2));
    for(int c=0; c < 4; ++c) {
      assertEquals("column " + c, false, batch.cols[c].isNull[2]);
    }
  }

  @Test
  public void testStructs() throws Exception {
    StringReader input = new StringReader(
        "1,2,3,4\n" +
        "5,6,7,8\n"
    );
    TypeDescription schema = TypeDescription.fromString(
        "struct<a:int,b:struct<c:int,d:int>,e:int>");
    RecordReader reader = new CsvReader(input, null, 1, schema, ',', '\'',
        '\\', 0, "null");
    VectorizedRowBatch batch = schema.createRowBatch();
    assertEquals(true, reader.nextBatch(batch));
    assertEquals(2, batch.size);
    int nextVal = 1;
    for(int r=0; r < 2; ++r) {
      assertEquals("row " + r, nextVal++, ((LongColumnVector) batch.cols[0]).vector[r]);
      StructColumnVector b = (StructColumnVector) batch.cols[1];
      assertEquals("row " + r, nextVal++, ((LongColumnVector) b.fields[0]).vector[r]);
      assertEquals("row " + r, nextVal++, ((LongColumnVector) b.fields[1]).vector[r]);
      assertEquals("row " + r, nextVal++, ((LongColumnVector) batch.cols[2]).vector[r]);
    }
    assertEquals(false, reader.nextBatch(batch));
  }
}
