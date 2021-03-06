// Copyright 2013 Foursquare Labs Inc. All Rights Reserved.

package com.foursquare.rogue.spindle

import com.foursquare.index.UntypedMongoIndex
import com.foursquare.rogue.{DBCollectionFactory, Query => RogueQuery}
import com.foursquare.spindle.{IndexParser, UntypedMetaRecord}
import com.mongodb.{DB, DBCollection}
import scala.collection.immutable.ListMap
import scala.collection.mutable.ConcurrentMap

trait SpindleDBCollectionFactory extends DBCollectionFactory[UntypedMetaRecord] {
  def getDB(meta: UntypedMetaRecord): DB = {
    getPrimaryDB(meta)
  }

  def getPrimaryDB(meta: UntypedMetaRecord): DB

  override def getDBCollection[M <: UntypedMetaRecord](query: RogueQuery[M, _, _]): DBCollection =
    getDB(query.meta).getCollection(query.collectionName)

  override def getPrimaryDBCollection[M <: UntypedMetaRecord](query: RogueQuery[M, _, _]): DBCollection = {
    getPrimaryDBCollection(query.meta)
  }

  def getPrimaryDBCollection(meta: UntypedMetaRecord): DBCollection = {
    getPrimaryDB(meta).getCollection(getCollection(meta))
  }

  override def getInstanceName[M <: UntypedMetaRecord](query: RogueQuery[M, _, _]): String = {
    getIdentifier(query.meta)
  }

  def getIdentifier(meta: UntypedMetaRecord): String = {
    meta.annotations.get("mongo_identifier").getOrElse {
      throw new Exception("Add a mongo_identifier annotation to the Thrift definition for this class.")
    }
  }

  def getCollection(meta: UntypedMetaRecord): String = {
    meta.annotations.get("mongo_collection").getOrElse {
      throw new Exception("Add a mongo_collection annotation to the Thrift definition for this class.")
    }
  }

  protected def indexCache: Option[ConcurrentMap[UntypedMetaRecord, List[UntypedMongoIndex]]]

  /**
   * Retrieves the list of indexes declared for the record type associated with a
   * query. If the record type doesn't declare any indexes, then returns None.
   * @param query the query
   * @return the list of indexes, or an empty list.
   */
  override def getIndexes[M <: UntypedMetaRecord](query: RogueQuery[M, _, _]): Option[List[UntypedMongoIndex]] = {
    val cachedIndexes = indexCache.flatMap(_.get(query.meta))
    if (cachedIndexes.isDefined) {
      cachedIndexes
    } else {
      val rv = 
        for (indexes <- IndexParser.parse(query.meta.annotations).right.toOption) yield {
          for (index <- indexes.toList) yield {
            val entries = index.map(entry => (entry.fieldName, entry.indexType))
            new SpindleMongoIndex(ListMap(entries: _*))
          }
        }

      // Update the cache
      for {
        indexes <- rv
        cache <- indexCache
      } {
        cache.put(query.meta, indexes)
      }

      rv
    }
  }
}

private[spindle] class SpindleMongoIndex(override val asListMap: ListMap[String, Any]) extends UntypedMongoIndex
