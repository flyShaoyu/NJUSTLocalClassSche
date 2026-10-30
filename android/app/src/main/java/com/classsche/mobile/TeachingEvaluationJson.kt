package com.classsche.mobile

import org.json.JSONArray
import org.json.JSONObject

internal object TeachingEvaluationJson {
  fun settings(input: JSONObject) = TeachingEvaluationParser.Settings(input.optInt("option",0),input.optInt("lastOption",1),input.optString("comment").trim())
  fun plan(plan: TeachingEvaluationEngine.Plan) = JSONObject().put("id",plan.id).put("courses",JSONArray().apply {
    plan.courses.forEach { put(JSONObject().put("title",it.course.title).put("choices",JSONArray(it.choices)).put("url",it.course.listUrl)) }
  }).put("notices",JSONArray(plan.notices))
  private fun items(items: List<TeachingEvaluationEngine.Item>) = JSONArray().apply {
    items.forEach { put(JSONObject().put("title",it.title).put("state",it.state).put("message",it.message).put("url",it.url)) }
  }
  fun progress(progress: TeachingEvaluationEngine.Progress) = JSONObject().put("stage",progress.stage).put("done",progress.done)
    .put("total",progress.total).put("title",progress.title).put("items",items(progress.items))
  fun outcome(result: TeachingEvaluationEngine.Outcome) = JSONObject().put("items",items(result.items)).put("stopped",result.stopped).put("notices",JSONArray(result.notices))
}
