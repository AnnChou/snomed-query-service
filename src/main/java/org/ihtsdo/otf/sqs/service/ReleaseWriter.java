package org.ihtsdo.otf.sqs.service;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.LongField;
import org.apache.lucene.document.SortedNumericDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.ihtsdo.otf.snomedboot.domain.Concept;
import org.ihtsdo.otf.snomedboot.domain.Description;
import org.ihtsdo.otf.snomedboot.domain.Relationship;
import org.ihtsdo.otf.sqs.domain.ConceptFieldNames;
import org.ihtsdo.otf.sqs.domain.DescriptionFieldNames;
import org.ihtsdo.otf.sqs.domain.RelationshipFieldNames;
import org.ihtsdo.otf.sqs.service.store.ReleaseStore;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

public class ReleaseWriter implements AutoCloseable {

	private final IndexWriter iwriter;
	private SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd");

	public ReleaseWriter(ReleaseStore releaseStore) throws IOException {
		IndexWriterConfig config = new IndexWriterConfig(releaseStore.createAnalyzer());
		iwriter = new IndexWriter(releaseStore.getDirectory(), config);
	}

	public void addConcept(Concept concept, boolean isStatedRelationship) throws IOException, ParseException {
		List<Document> documents = new ArrayList<>();
		documents.add(getConceptDocument(concept, isStatedRelationship));
		iwriter.addDocuments(documents);
	}

	private void addCardinalityToDocument(Concept concept, Document doc, boolean isStatedRelationship) throws ParseException {
		final MultiValueMap<String, String> attributeGroups = new LinkedMultiValueMap<>();
		Date maxDate = dateFormat.parse(concept.getEffectiveTime());
		for (Relationship relationship : concept.getRelationships()) {
			if (relationship != null) {
				if (isStatedRelationship && "900000000000011006".equals(relationship.getCharacteristicTypeId())) {
					continue;
				} else if (!isStatedRelationship && "900000000000010007".equals(relationship.getCharacteristicTypeId())) {
					continue;
				}
				if (relationship.getEffectiveTime() != null && !relationship.getEffectiveTime().isEmpty() && 
						dateFormat.parse(relationship.getEffectiveTime()).after(maxDate)) {
					maxDate = dateFormat.parse(relationship.getEffectiveTime());
				}
				attributeGroups.add(relationship.getTypeId(), relationship.getRelationshipGroup());
			}
		}
		doc.add(new StringField(ConceptFieldNames.EFFECTIVE_TIME, dateFormat.format(maxDate), Field.Store.YES));
		addAttributeCardinalityDocument(doc, attributeGroups);
	}

	private void addAttributeCardinalityDocument(Document doc, final MultiValueMap<String, String> attributeGroups) {
		for (String key : attributeGroups.keySet()) {
			int totalCount = 0;
			Iterator<String> itrator = attributeGroups.get(key).iterator();
			List<String> roleGroups = new ArrayList<>();
			Map<String,Integer> groupCountMap = new HashMap<>();
			while (itrator.hasNext()) {
				String grp = itrator.next();
				totalCount++;
				if (!"0".equals(grp)) {
					roleGroups.add(grp);
					if (groupCountMap.containsKey(grp)) {
						groupCountMap.put(grp, groupCountMap.get(grp).intValue() + 1);
					} else {
						groupCountMap.put(grp, 1);
					}
				}
			}
			Set<String> distinctGroups = new HashSet<>(roleGroups);
			if (!distinctGroups.isEmpty()) {
				doc.add(new StringField(key + ConceptFieldNames.TOTAL_GROUPS, String.valueOf(distinctGroups.size()), Field.Store.YES));
				List<Integer> countList = new ArrayList<>(groupCountMap.values());
				java.util.Collections.sort(countList);
				if (!countList.isEmpty()) {
					Integer maxGroup = countList.get(countList.size()-1);
					doc.add(new StringField(key + ConceptFieldNames.GROUP_CARDINALITY, maxGroup.toString(), Field.Store.YES));
				}
			}
			//attributeGroupCardinality
			doc.add(new StringField(key + ConceptFieldNames.CARDINALITY, String.valueOf(totalCount), Field.Store.YES));
		}
	}

	private Document getConceptDocument(Concept concept, boolean isStatedRelationship) throws ParseException {
		Document conceptDoc = new Document();
		conceptDoc.add(new StringField("type", "concept", Field.Store.YES));
		conceptDoc.add(new StringField(ConceptFieldNames.ID, concept.getId().toString(), Field.Store.YES));
		conceptDoc.add(new StringField(ConceptFieldNames.ACTIVE, concept.isActive() ? "1" : "0", Field.Store.YES));
		conceptDoc.add(new StringField(ConceptFieldNames.MODULE_ID, concept.getModuleId(), Field.Store.YES));
		conceptDoc.add(new StringField(ConceptFieldNames.DEFINITION_STATUS_ID, concept.getDefinitionStatusId(), Field.Store.YES));
		if (concept.getFsn() == null) {
			throw new IllegalStateException("FSN can't be null for concept:" + concept.getId());
		}
		conceptDoc.add(new TextField(ConceptFieldNames.FSN, concept.getFsn(), Field.Store.YES));
		conceptDoc.add(new SortedNumericDocValuesField(ConceptFieldNames.FSN_LENGTH, concept.getFsn() != null ? concept.getFsn().length() : 100));
		final MultiValueMap<String, String> attributes = isStatedRelationship ? concept.getStatedAttributes() : concept.getInferredAttributes();
		for (String type : attributes.keySet()) {
			for (String value : attributes.get(type)) {
				conceptDoc.add(new StringField(type, value, Field.Store.YES));
			}
		}
		final Set<Long> ancestorIds = concept.getInferredAncestorIds();
		for (Long ancestorId : ancestorIds) {
			conceptDoc.add(new StringField(ConceptFieldNames.ANCESTOR, ancestorId.toString(), Field.Store.YES));
		}
		for (Long memberRefsetId : concept.getMemberOfRefsetIds()) {
			conceptDoc.add(new StringField(ConceptFieldNames.MEMBER_OF, memberRefsetId.toString(), Field.Store.YES));
		}
		addCardinalityToDocument(concept, conceptDoc, isStatedRelationship);
		return conceptDoc;
	}

	private Document getRelationshipDocument(org.ihtsdo.otf.snomedboot.domain.Relationship relationship) {
		Document doc = new Document();
		doc.add(new StringField(RelationshipFieldNames.ID, relationship.getId(), Field.Store.YES));
		doc.add(new StringField(RelationshipFieldNames.EFFECTIVE_TIME, relationship.getEffectiveTime(), Field.Store.YES));
		doc.add(new StringField(RelationshipFieldNames.ACTIVE, relationship.getActive(), Field.Store.YES));
		doc.add(new StringField(RelationshipFieldNames.MODULE_ID, relationship.getModuleId(), Field.Store.YES));
		doc.add(new StringField(RelationshipFieldNames.SOURCE_ID, relationship.getSourceId(), Field.Store.YES));
		doc.add(new StringField(RelationshipFieldNames.DESTINATION_ID, relationship.getDestinationId(), Field.Store.YES));
		doc.add(new StringField(RelationshipFieldNames.RELATIONSHIP_GROUP, relationship.getRelationshipGroup(), Field.Store.YES));
		doc.add(new StringField(RelationshipFieldNames.TYPE_ID, relationship.getTypeId(), Field.Store.YES));
		doc.add(new StringField(RelationshipFieldNames.CHARACTERISTIC_TYPE_ID, relationship.getCharacteristicTypeId(), Field.Store.YES));
		doc.add(new StringField(RelationshipFieldNames.MODIFIER_ID, relationship.getModifierId(), Field.Store.YES));
		return doc;
	}

	private Document getDescriptionDocument(Description description) {
		Document doc = new Document();
		doc.add(new LongField(DescriptionFieldNames.ID, description.getId(), Field.Store.YES));
		doc.add(new StringField(DescriptionFieldNames.TERM, description.getTerm(), Field.Store.YES));
		doc.add(new LongField(DescriptionFieldNames.CONCEPT_ID, description.getConceptId(), Field.Store.YES));
		return doc;
	}

	@Override
	public void close() throws IOException {
		iwriter.close();
	}
}
