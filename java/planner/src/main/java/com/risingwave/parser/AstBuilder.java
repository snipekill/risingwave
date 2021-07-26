/*
 * Licensed to Crate.io GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package com.risingwave.parser;

import static java.util.stream.Collectors.toList;

import com.risingwave.parser.antlr.v4.SqlBaseBaseVisitor;
import com.risingwave.parser.antlr.v4.SqlBaseParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.antlr.v4.runtime.ParserRuleContext;
import org.apache.calcite.schema.ColumnStrategy;
import org.apache.calcite.sql.SqlBasicTypeNameSpec;
import org.apache.calcite.sql.SqlDataTypeSpec;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.ddl.SqlDdlNodes;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.SqlTypeName;

class AstBuilder extends SqlBaseBaseVisitor<SqlNode> {

  @Override
  public SqlNode visitSingleStatement(SqlBaseParser.SingleStatementContext context) {
    return visit(context.statement());
  }

  //  Statements
  @Override
  public SqlNode visitCreateTable(SqlBaseParser.CreateTableContext context) {
    boolean notExists = context.EXISTS() != null;
    // FIXME: No clustered by/partitioned by/query for creating table now.
    // Visit each element (columns) and convert into Calcite format.
    ArrayList<SqlNode> arr = new ArrayList<SqlNode>();
    for (SqlBaseParser.TableElementContext eleCtx : context.tableElement()) {
      arr.add(visit(eleCtx));
    }
    SqlNodeList tableElements = SqlNodeList.of(getParserPos(context.tableElement(0)), arr);
    SqlNode tableName = visit(context.table());
    return SqlDdlNodes.createTable(
        getParserPos(context), false, notExists, (SqlIdentifier) tableName, tableElements, null);
  }

  // Column / Table definition
  @Override
  public SqlNode visitColumnDefinition(SqlBaseParser.ColumnDefinitionContext context) {
    SqlIdentifier ident =
        new SqlIdentifier(context.ident().getText(), getParserPos(context.ident()));
    SqlNode type = visit(context.dataType());
    List<SqlIdentifier> constraint =
        visitCollection(context.columnConstraint(), SqlIdentifier.class);
    // Now column constraint is expected to have only one.
    if (constraint.size() == 0 || constraint.size() > 1) {
      throw new ParsingException(
          "Do not support zero or multiple column constraint for now",
          null,
          context.getStart().getLine(),
          context.getStart().getCharPositionInLine());
    }
    return SqlDdlNodes.column(
        getParserPos(context),
        ident,
        (SqlDataTypeSpec) type,
        null,
        constraint.get(0).names.get(0).equals("NOT_NULL")
            ? ColumnStrategy.NOT_NULLABLE
            : ColumnStrategy.NULLABLE);
  }

  @Override
  public SqlNode visitColumnConstraintPrimaryKey(
      SqlBaseParser.ColumnConstraintPrimaryKeyContext context) {
    // Do not find a proper type for key constraint. In calcite, column strategy class is not a
    // sqlnode. Use SqlIdentifier to replace.
    return new SqlIdentifier("PRIMARY_KEY", getParserPos(context));
  }

  @Override
  public SqlNode visitColumnConstraintNotNull(
      SqlBaseParser.ColumnConstraintNotNullContext context) {
    return new SqlIdentifier("NOT_NULL", getParserPos(context));
  }

  /*
   * case sensitivity like it is in postgres
   * see also http://www.thenextage.com/wordpress/postgresql-case-sensitivity-part-1-the-ddl/
   *
   * unfortunately this has to be done in the parser because afterwards the
   * knowledge of the IDENT / QUOTED_IDENT difference is lost
   */
  @Override
  public SqlNode visitUnquotedIdentifier(SqlBaseParser.UnquotedIdentifierContext context) {
    return new SqlIdentifier(context.getText().toLowerCase(Locale.ENGLISH), getParserPos(context));
  }

  @Override
  public SqlNode visitTableName(SqlBaseParser.TableNameContext ctx) {
    return visit(ctx.qname());
  }

  @Override
  public SqlNode visitIdentDataType(SqlBaseParser.IdentDataTypeContext context) {
    SqlNode ident = visit(context.ident());
    SqlBasicTypeNameSpec type =
        new SqlBasicTypeNameSpec(convertToType((SqlIdentifier) ident), getParserPos(context));
    return new SqlDataTypeSpec(type, getParserPos(context));
  }

  @Override
  public SqlNode visitMaybeParametrizedDataType(
      SqlBaseParser.MaybeParametrizedDataTypeContext context) {
    return visit(context.baseDataType());
  }

  // Helpers
  private static SqlParserPos getParserPos(ParserRuleContext context) {
    return new SqlParserPos(
        context.getStart().getLine(),
        context.getStart().getCharPositionInLine(),
        context.getStop().getLine(),
        context.getStop().getCharPositionInLine());
  }

  private SqlTypeName convertToType(SqlIdentifier ident) {
    switch (ident.names.get(0).toUpperCase()) {
      case "INT":
      case "INTEGER":
        return SqlTypeName.INTEGER;
      default:
        throw new ParsingException("Un supported ident type: " + ident.names.get(0));
    }
  }

  private <T> List<T> visitCollection(List<? extends ParserRuleContext> contexts, Class<T> clazz) {
    return contexts.stream().map(this::visit).map(clazz::cast).collect(toList());
  }
}
