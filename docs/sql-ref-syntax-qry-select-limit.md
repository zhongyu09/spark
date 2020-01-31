---
layout: global
title: LIMIT Clause
displayTitle: LIMIT Clause
license: |
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at
 
     http://www.apache.org/licenses/LICENSE-2.0
 
  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
---
The <code>LIMIT</code> clause is used to constrain the number of rows returned by
the [SELECT](sql-ref-syntax-qry-select.html) statement. In general, this clause
is used in conjuction with [ORDER BY](sql-ref-syntax-qry-select-orderby.html) to
ensure that the results are deterministic.

### Syntax
{% highlight sql %}
LIMIT { ALL | integer_expression }
{% endhighlight %}

### Parameters
<dl>
  <dt><code><em>ALL</em></code></dt>
  <dd>
    If specified, the query returns all the rows. In other words, no limit is applied if this
    option is specified.
  </dd>
  <dt><code><em>integer_expression</em></code></dt>
  <dd>
    Specifies an expression that returns an integer. 
  </dd>
</dl>

### Examples
{% highlight sql %}
CREATE TABLE person (name STRING, age INT);
INSERT INTO person VALUES
    ('Zen Hui', 25), 
    ('Anil B', 18), 
    ('Shone S', 16), 
    ('Mike A', 25),
    ('John A', 18), 
    ('Jack N', 16);
                        
-- Select the first two rows.
SELECT name, age FROM person ORDER BY name LIMIT 2;

  +------+---+
  |name  |age|
  +------+---+
  |Anil B|18 |
  |Jack N|16 |
  +------+---+

-- Specifying ALL option on LIMIT returns all the rows.
SELECT name, age FROM person ORDER BY name LIMIT ALL;

  +-------+---+
  |name   |age|
  +-------+---+
  |Anil B |18 |
  |Jack N |16 |
  |John A |18 |
  |Mike A |25 |
  |Shone S|16 |
  |Zen Hui|25 |
  +-------+---+

-- A function expression as an input to limit.
SELECT name, age FROM person ORDER BY name LIMIT length('SPARK')

  +-------+---+
  |   name|age|
  +-------+---+
  | Anil B| 18|
  | Jack N| 16|
  | John A| 18|
  | Mike A| 25|
  |Shone S| 16|
  +-------+---+
{% endhighlight %}

### Related Clauses
- [SELECT Main](sql-ref-syntax-qry-select.html)
- [WHERE Clause](sql-ref-syntax-qry-select-where.html)
- [GROUP BY Clause](sql-ref-syntax-qry-select-groupby.html)
- [HAVING Clause](sql-ref-syntax-qry-select-having.html)
- [ORDER BY Clause](sql-ref-syntax-qry-select-orderby.html)
- [SORT BY Clause](sql-ref-syntax-qry-select-sortby.html)
- [CLUSTER BY Clause](sql-ref-syntax-qry-select-clusterby.html)
- [DISTRIBUTE BY Clause](sql-ref-syntax-qry-select-distribute-by.html)
