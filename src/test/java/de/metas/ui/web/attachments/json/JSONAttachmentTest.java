package de.metas.ui.web.attachments.json;

import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;

import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.metas.ui.web.window.datatypes.DocumentId;

/*
 * #%L
 * metasfresh-webui-api
 * %%
 * Copyright (C) 2019 metas GmbH
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program. If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */

public class JSONAttachmentTest
{

	private ObjectMapper jsonObjectMapper;

	@Before
	public void init()
	{
		jsonObjectMapper = new ObjectMapper();
	}

	@Test
	public void testSerializeDeserialize() throws IOException
	{
		final DocumentId id = DocumentId.of("12345string");
		final JSONAttachment jSONAttachment = new JSONAttachment(id, "test");
		jSONAttachment.setAllowDelete(true);
		final String json = jsonObjectMapper.writeValueAsString(jSONAttachment);

		final JSONAttachment attachment = jsonObjectMapper.readValue(json, JSONAttachment.class);
		assertEquals(jSONAttachment.getId(), attachment.getId());
		assertEquals(jSONAttachment.getName(), attachment.getName());
		assertTrue(attachment.isAllowDelete());
	}
}
