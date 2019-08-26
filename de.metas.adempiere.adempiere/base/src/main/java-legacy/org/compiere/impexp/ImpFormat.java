/******************************************************************************
 * Product: Adempiere ERP & CRM Smart Business Solution *
 * Copyright (C) 1999-2006 ComPiere, Inc. All Rights Reserved. *
 * This program is free software; you can redistribute it and/or modify it *
 * under the terms version 2 of the GNU General Public License as published *
 * by the Free Software Foundation. This program is distributed in the hope *
 * that it will be useful, but WITHOUT ANY WARRANTY; without even the implied *
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. *
 * See the GNU General Public License for more details. *
 * You should have received a copy of the GNU General Public License along *
 * with this program; if not, write to the Free Software Foundation, Inc., *
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA. *
 * For the text or an alternative of this public license, you may reach us *
 * ComPiere, Inc., 2620 Augustine Dr. #245, Santa Clara, CA 95054, USA *
 * or via info@compiere.org or http://www.compiere.org/license.html *
 *****************************************************************************/
package org.compiere.impexp;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.adempiere.ad.trx.api.ITrx;
import org.adempiere.exceptions.AdempiereException;
import org.adempiere.exceptions.DBException;
import org.adempiere.service.ClientId;
import org.adempiere.util.lang.ITableRecordReference;
import org.adempiere.util.lang.impl.TableRecordReference;
import org.compiere.model.I_C_DataImport;
import org.compiere.model.I_GL_Journal;
import org.compiere.util.DB;
import org.slf4j.Logger;

import com.google.common.collect.ImmutableList;

import de.metas.logging.LogManager;
import de.metas.organization.OrgId;
import de.metas.user.UserId;
import de.metas.util.Check;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;

/**
 * Import Format a Row
 *
 * @author Jorg Janke
 * @version $Id: ImpFormat.java,v 1.3 2006/07/30 00:51:05 jjanke Exp $
 */
public final class ImpFormat
{
	private static final Logger logger = LogManager.getLogger(ImpFormat.class);

	@Getter
	private final String name;
	private final ImpFormatType formatType;
	@Getter
	private final boolean multiLine;
	@Getter
	private final boolean manualImport;

	/** The Table to be imported */
	private final ImpFormatTableInfo tableInfo;

	private final ImmutableList<ImpFormatRow> rows;

	@Builder
	private ImpFormat(
			@NonNull final String name,
			@NonNull final ImpFormatType formatType,
			final boolean multiLine,
			final boolean manualImport,
			@NonNull final ImpFormatTableInfo tableInfo,
			@NonNull final List<ImpFormatRow> rows)
	{
		Check.assumeNotEmpty(name, "name is not empty");
		Check.assumeNotEmpty(rows, "rows is not empty");

		this.name = name;
		this.formatType = formatType;
		this.multiLine = multiLine;
		this.manualImport = manualImport;
		this.tableInfo = tableInfo;
		this.rows = ImmutableList.copyOf(rows);
	}

	public String getTableName()
	{
		return tableInfo.getTableName();
	}

	/**
	 * @return import Format Row or null
	 */
	public ImpFormatRow getRow(final int index)
	{
		if (index >= 0 && index < rows.size())
		{
			return rows.get(index);
		}
		else
		{
			return null;
		}
	}	// getRow

	public int getRowCount()
	{
		return rows.size();
	}	// getRowCount

	/*************************************************************************
	 * Parse Line returns ArrayList of values
	 *
	 * @param line line
	 * @param withLabel true if with label
	 * @param trace create trace info
	 * @param ignoreEmpty - ignore empty fields
	 * @return Array of values
	 */
	@Deprecated
	public String[] parseLine(final String line, final boolean withLabel, final boolean trace, final boolean ignoreEmpty)
	{
		final List<String> result = new ArrayList<>();
		for (final ImpDataCell cell : parseDataCells(line))
		{
			if (ignoreEmpty && cell.isEmpty())
			{
				continue;
			}

			result.add(withLabel ? cell.getValueAsSQL() : cell.getValueAsString());
		}
		return result.toArray(new String[result.size()]);
	}

	public List<ImpDataCell> parseDataCells(final String line)
	{
		final List<ImpDataCell> cells = new ArrayList<>();
		// for all columns
		for (int index = 0; index < rows.size(); index++)
		{
			final ImpFormatRow impFormatRow = rows.get(index);

			// Get Data
			String cellValueRaw = null;
			if (impFormatRow.isConstant())
			{
				cellValueRaw = "Constant";
			}
			else if (formatType.equals(ImpFormatType.FIXED_POSITION))
			{
				// check length
				if (impFormatRow.getStartNo() > 0 && impFormatRow.getEndNo() <= line.length())
				{
					cellValueRaw = line.substring(impFormatRow.getStartNo() - 1, impFormatRow.getEndNo());
				}
			}
			else
			{
				cellValueRaw = parseFlexFormat(line, formatType, impFormatRow.getStartNo());
			}
			if (cellValueRaw == null)
			{
				cellValueRaw = "";
			}

			final ImpDataCell cell = new ImpDataCell(impFormatRow);
			cell.setValue(cellValueRaw);
			cells.add(cell);
		}	// for all columns

		return ImmutableList.copyOf(cells);
	}	// parseLine

	/**
	 * Parse flexible line format. A bit inefficient as it always starts from the start
	 *
	 * @param line the line to be parsed
	 * @param formatType Comma or Tab
	 * @param fieldNo number of field to be returned
	 * @return field in lime or ""
	 * @throws IllegalArgumentException if format unknows
	 */
	private String parseFlexFormat(
			final String line,
			final ImpFormatType formatType,
			final int fieldNo)
	{
		final char QUOTE = '"';
		// check input
		char delimiter = ' ';
		if (formatType.equals(ImpFormatType.COMMA_SEPARATED))
		{
			delimiter = ',';
		}
		else if (formatType.equals(ImpFormatType.SEMICOLON_SEPARATED))
		{
			delimiter = ';';
		}
		else if (formatType.equals(ImpFormatType.TAB_SEPARATED))
		{
			delimiter = '\t';
		}
		else
		{
			throw new IllegalArgumentException("ImpFormat.parseFlexFormat - unknown format: " + formatType);
		}
		if (line == null || line.length() == 0 || fieldNo < 0)
		{
			return "";
		}

		// We need to read line sequentially as the fields may be delimited
		// with quotes (") when fields contain the delimiter
		// Example: "Artikel,bez","Artikel,""nr""",DEM,EUR
		// needs to result in - Artikel,bez - Artikel,"nr" - DEM - EUR
		int pos = 0;
		int length = line.length();
		for (int field = 1; field <= fieldNo && pos < length; field++)
		{
			final StringBuilder content = new StringBuilder();
			// two delimiter directly after each other
			if (line.charAt(pos) == delimiter)
			{
				pos++;
				continue;
			}
			// Handle quotes
			if (line.charAt(pos) == QUOTE)
			{
				pos++;  // move over beginning quote
				while (pos < length)
				{
					// double quote
					if (line.charAt(pos) == QUOTE && pos + 1 < length && line.charAt(pos + 1) == QUOTE)
					{
						content.append(line.charAt(pos++));
						pos++;
					}
					// end quote
					else if (line.charAt(pos) == QUOTE)
					{
						pos++;
						break;
					}
					// normal character
					else
					{
						content.append(line.charAt(pos++));
					}
				}
				// we should be at end of line or a delimiter
				if (pos < length && line.charAt(pos) != delimiter)
				{
					logger.info("Did not find delimiter at pos " + pos + " " + line);
				}
				pos++;  // move over delimiter
			}
			else
			// plain copy
			{
				while (pos < length && line.charAt(pos) != delimiter)
				{
					content.append(line.charAt(pos++));
				}
				pos++;  // move over delimiter
			}
			if (field == fieldNo)
			{
				return content.toString();
			}
		}

		// nothing found
		return "";
	}   // parseFlexFormat

	/*************************************************************************
	 * Insert/Update Database.
	 *
	 * @param ctx context
	 * @param line line
	 * @param trxName transaction
	 * @return
	 * @return reference to import table record
	 */
	public ITableRecordReference updateDB(
			@NonNull final ImpDataContext ctx,
			@NonNull final ImpDataLine line)
	{
		if (line == null || line.isEmpty())
		{
			throw new AdempiereException("No Line");
		}

		final List<ImpDataCell> nodes = line.getValues();
		if (nodes.isEmpty())
		{
			throw new AdempiereException("Nothing parsed");
		}

		// Standard Fields
		final ClientId clientId = ctx.getClientId();
		final OrgId orgId;
		if (I_GL_Journal.Table_Name.equals(getTableName())) // FIXME HARDCODED
		{
			orgId = OrgId.ANY;
		}
		else
		{
			orgId = ctx.getOrgId();
		}
		final UserId userId = ctx.getUserId();

		final String tableName = tableInfo.getTableName();
		final String tablePK = tableInfo.getTablePK();
		final String tableUnique1 = tableInfo.getTableUnique1();
		final String tableUnique2 = tableInfo.getTableUnique2();
		final String tableUniqueParent = tableInfo.getTableUniqueParent();
		final String tableUniqueChild = tableInfo.getTableUniqueChild();
		final boolean hasDataImportIdColumn = tableInfo.isHasDataImportIdColumn();

		//
		// Re-use the same ID if we already imported this record
		int importRecordId = 0;
		final ITableRecordReference importRecordRef = line.getImportRecordRef();
		if (importRecordRef != null
				&& importRecordRef.getTableName().equals(tableName)
				&& importRecordRef.getRecord_ID() > 0)
		{
			final int recordId = importRecordRef.getRecord_ID();

			// make sure it still exists
			final int count = DB.getSQLValue(ITrx.TRXNAME_ThreadInherited, "SELECT COUNT(1) FROM " + tableName + " WHERE " + tablePK + "=" + recordId);
			if (count == 1)
			{
				importRecordId = recordId;
			}
		}

		//
		// Check if the record is already there (by looking up by unique keys)
		if (importRecordId <= 0)
		{
			final StringBuilder sql = new StringBuilder("SELECT COUNT(*), MAX(")
					.append(tablePK).append(") FROM ").append(tableName)
					.append(" WHERE AD_Client_ID=").append(clientId.getRepoId()).append(" AND (");
			//
			String where1 = null;
			String where2 = null;
			String whereParentChild = null;
			for (final ImpDataCell node : nodes)
			{
				if (node.isEmptyOrZero())
				{
					continue;
				}

				final String columnName = node.getColumnName();
				if (columnName.equals(tableUnique1))
				{
					where1 = node.getColumnNameEqualsValueSql();
				}
				else if (columnName.equals(tableUnique2))
				{
					where2 = node.getColumnNameEqualsValueSql();
				}
				else if (columnName.equals(tableUniqueParent) || columnName.equals(tableUniqueChild))
				{
					if (whereParentChild == null)
					{
						whereParentChild = node.getColumnNameEqualsValueSql();
					}
					else
					{
						whereParentChild += " AND " + node.getColumnNameEqualsValueSql();
					}
				}
			}

			final StringBuilder sqlFindExistingRecord = new StringBuilder();
			if (where1 != null)
			{
				sqlFindExistingRecord.append(where1);
			}
			if (where2 != null)
			{
				if (sqlFindExistingRecord.length() > 0)
				{
					sqlFindExistingRecord.append(" OR ");
				}
				sqlFindExistingRecord.append(where2);
			}
			if (whereParentChild != null && whereParentChild.indexOf(" AND ") != -1)	// need to have both criteria
			{
				if (sqlFindExistingRecord.length() > 0)
				{
					sqlFindExistingRecord.append(" OR (").append(whereParentChild).append(")");	// may have only one
				}
				else
				{
					sqlFindExistingRecord.append(whereParentChild);
				}
			}
			sql.append(sqlFindExistingRecord).append(")");
			//
			PreparedStatement pstmt = null;
			ResultSet rs = null;
			try
			{
				if (sqlFindExistingRecord.length() > 0)
				{
					pstmt = DB.prepareStatement(sql.toString(), ITrx.TRXNAME_ThreadInherited);
					rs = pstmt.executeQuery();
					if (rs.next())
					{
						final int count = rs.getInt(1);
						if (count == 1)
						{
							importRecordId = rs.getInt(2);
						}
					}
				}
			}
			catch (SQLException e)
			{
				throw new DBException(e, sql.toString());
			}
			finally
			{
				DB.close(rs, pstmt);
			}
		}

		//
		// Insert into import table (only mandatory columns)
		if (importRecordId <= 0)
		{
			importRecordId = DB.getNextID(clientId.getRepoId(), tableName, ITrx.TRXNAME_None);
			if (importRecordId <= 0)
			{
				throw new AdempiereException("Cannot acquire next ID for " + tableName);
			}

			final StringBuilder sql = new StringBuilder("INSERT INTO ")
					.append(tableName).append("(").append(tablePK).append(",")
					.append("AD_Client_ID,AD_Org_ID,Created,CreatedBy,Updated,UpdatedBy,IsActive")	// StdFields
					.append(") VALUES (").append(importRecordId).append(",")
					.append(clientId.getRepoId()).append(",").append(orgId.getRepoId())
					.append(",now(),").append(userId.getRepoId()).append(",now(),").append(userId.getRepoId()).append(",'Y'")
					.append(")");
			//
			final int no = DB.executeUpdateEx(sql.toString(), ITrx.TRXNAME_ThreadInherited);
			if (no != 1)
			{
				throw new DBException("Failed inserting the record");
			}
			logger.trace("New ID={}", importRecordId);
		}
		else
		{
			logger.trace("Old ID={}", importRecordId);
		}

		//
		// Update import row
		{
			final StringBuilder sqlUpdate = new StringBuilder("UPDATE ")
					.append(tableName).append(" SET ");
			for (final ImpDataCell node : nodes)
			{
				if (node.isEmpty())
				{
					continue;
				}
				sqlUpdate.append(node.getColumnNameEqualsValueSql()).append(",");		// column=value
			}

			if (hasDataImportIdColumn && line.getDataImportId() != null)
			{
				sqlUpdate.append(I_C_DataImport.COLUMNNAME_C_DataImport_ID).append("=").append(line.getDataImportId().getRepoId()).append(",");
			}

			sqlUpdate.append("IsActive='Y',Processed='N',I_IsImported='N',Updated=now(),UpdatedBy=").append(userId.getRepoId());
			sqlUpdate.append(" WHERE ").append(tablePK).append("=").append(importRecordId);
			//
			final int no = DB.executeUpdateEx(sqlUpdate.toString(), ITrx.TRXNAME_ThreadInherited);
			if (no != 1)
			{
				throw new DBException("Failed updating the record");
			}
		}

		return TableRecordReference.of(tableName, importRecordId);
	}
}	// ImpFormat
