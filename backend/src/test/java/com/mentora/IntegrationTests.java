package com.mentora;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentora.ai.GeminiService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Runs against a throw-away PostgreSQL database (mentora_test) that is wiped and re-migrated on every run. Gemini is mocked. */
@SpringBootTest(properties = {
    "spring.datasource.url=${TEST_DB_URL:jdbc:postgresql://localhost:5433/mentora_test}",
    "spring.flyway.clean-disabled=false",
    "mentora.storage-dir=target/test-storage",
    "mentora.seed-demo-data=true"})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IntegrationTests {

  @TestConfiguration
  static class CleanDb {
    @Bean FlywayMigrationStrategy strategy() { return (Flyway f) -> { f.clean(); f.migrate(); }; }
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @MockitoBean GeminiService gemini;

  private JsonNode call(MockHttpServletRequestBuilder b, String token, int expectedStatus) throws Exception {
    if (token != null) b.header("Authorization", "Bearer " + token);
    var res = mvc.perform(b).andReturn().getResponse();
    assertEquals(expectedStatus, res.getStatus(), "unexpected status for " + b + ": " + res.getContentAsString());
    return res.getContentAsString().isBlank() ? null : json.readTree(res.getContentAsString());
  }

  private JsonNode login(String email, String pw) throws Exception {
    return call(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\",\"password\":\"" + pw + "\"}"), null, 200);
  }
  private String token(String email, String pw) throws Exception { return login(email, pw).get("token").asText(); }

  @Test @Order(1) void loginAndJwt() throws Exception {
    JsonNode ok = login("mentor1@mentora.edu", "Mentor@123");
    assertEquals("MENTOR", ok.get("user").get("role").asText());
    call(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"mentor1@mentora.edu\",\"password\":\"wrong\"}"), null, 401);
    call(get("/api/auth/me"), null, 401);
    call(get("/api/auth/me"), ok.get("token").asText() + "tampered", 401);
    assertEquals("mentor1@mentora.edu", call(get("/api/auth/me"), ok.get("token").asText(), 200).get("email").asText());
  }

  @Test @Order(2) void passwordsAreHashedWithBcrypt(@Autowired com.mentora.repo.UserRepository users) {
    assertTrue(users.findByEmailIgnoreCase("admin@mentora.edu").orElseThrow().getPasswordHash().startsWith("$2"));
  }

  @Test @Order(3) void roleBasedAuthorization() throws Exception {
    String student = token("1rn23is003@mentora.edu", "Student@123");
    String mentor = token("mentor1@mentora.edu", "Mentor@123");
    call(get("/api/admin/stats"), student, 403);
    call(get("/api/admin/stats"), mentor, 403);
    call(get("/api/mentor/dashboard"), student, 403);
    call(get("/api/student/dashboard"), mentor, 403);
    call(get("/api/admin/stats"), token("admin@mentora.edu", "Admin@123"), 200);
  }

  @Test @Order(4) void mentorSeesOnlyAssignedStudents() throws Exception {
    String m1 = token("mentor1@mentora.edu", "Mentor@123"), m2 = token("mentor2@mentora.edu", "Mentor@123");
    JsonNode l1 = call(get("/api/mentor/students").param("size", "50"), m1, 200), l2 = call(get("/api/mentor/students").param("size", "50"), m2, 200);
    assertEquals(33, l1.get("total").asInt());
    assertEquals(32, l2.get("total").asInt());
    long overlap = 0;
    for (JsonNode a : l1.get("content")) for (JsonNode b : l2.get("content")) if (a.get("studentId").equals(b.get("studentId"))) overlap++;
    assertEquals(0, overlap);
    long other = l2.get("content").get(0).get("studentId").asLong();
    // tampering with ids / urls / query params
    call(get("/api/students/" + other + "/attendance"), m1, 403);
    call(get("/api/students/" + other + "/marks"), m1, 403);
    call(get("/api/students/" + other + "/results"), m1, 403);
    call(get("/api/students/" + other + "/profile"), m1, 403);
    call(get("/api/students/" + other + "/leaves"), m1, 403);
    call(get("/api/students/" + other + "/feedback"), m1, 403);
    call(get("/api/students/" + other + "/observations"), m1, 403);
    call(get("/api/messages/" + other), m1, 403);
    call(post("/api/students/" + other + "/feedback").contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"x\"}"), m1, 403);
    call(get("/api/mentor/marks-grid").param("subjectId", "99999"), m1, 200);
    JsonNode grid = call(get("/api/mentor/attendance-grid"), m1, 200);
    assertEquals(33, grid.get("rows").size());
  }

  @Test @Order(5) void studentSeesOnlyOwnRecords() throws Exception {
    JsonNode s3 = login("1rn23is003@mentora.edu", "Student@123"), s4 = login("1rn23is004@mentora.edu", "Student@123");
    long id3 = s3.get("user").get("studentId").asLong(), id4 = s4.get("user").get("studentId").asLong();
    String t3 = s3.get("token").asText();
    call(get("/api/students/" + id3 + "/marks"), t3, 200);
    call(get("/api/students/" + id4 + "/marks"), t3, 403);
    call(get("/api/students/" + id4 + "/attendance"), t3, 403);
    call(get("/api/students/" + id4 + "/analytics"), t3, 403);
    call(get("/api/students/" + id3 + "/observations"), t3, 403);   // private mentor notes
    call(get("/api/messages/" + id4), t3, 403);
  }

  @Test @Order(6) void attendanceAndMarksAreCalculatedFromData() throws Exception {
    String t = token("1rn23is004@mentora.edu", "Student@123");
    long id = login("1rn23is004@mentora.edu", "Student@123").get("user").get("studentId").asLong();
    JsonNode att = call(get("/api/students/" + id + "/attendance"), t, 200);
    int conducted = att.get("overall").get("conducted").asInt();
    int attended = att.get("overall").get("attended").asInt();
    assertEquals(Math.round(attended * 1000.0 / conducted) / 10.0, att.get("overall").get("percent").asDouble(), 0.001);
    assertTrue(att.get("overall").get("percent").asDouble() < att.get("threshold").asDouble(), "scenario: low attendance student");
    JsonNode marks = call(get("/api/students/" + id + "/marks"), t, 200);
    for (JsonNode row : marks.get("rows")) {
      JsonNode m = row.get("marks");
      assertEquals(m.get("iaConverted").asDouble() + m.get("actConverted").asDouble(), m.get("total").asDouble(), 0.001);
    }
    call(get("/api/students/" + id + "/attendance").param("month", "bogus"), t, 400);
    JsonNode res = call(get("/api/students/" + id + "/results"), t, 200);
    assertTrue(res.get("cgpa").asDouble() > 0);
  }

  @Test @Order(7) void leaveWorkflowWithDocumentAndApproval() throws Exception {
    JsonNode s = login("1rn23is003@mentora.edu", "Student@123");
    String st = s.get("token").asText(), m1 = token("mentor1@mentora.edu", "Mentor@123"), m2 = token("mentor2@mentora.edu", "Mentor@123");
    long sid = s.get("user").get("studentId").asLong();
    double absentBefore = call(get("/api/students/" + sid + "/attendance"), st, 200).get("overall").get("absent").asDouble();
    MockMultipartFile pdf = new MockMultipartFile("files", "cert.pdf", "application/pdf", "%PDF-1.4 test".getBytes());
    var req = multipart("/api/leaves").file(pdf).param("type", "MEDICAL").param("startDate", java.time.LocalDate.now().toString()).param("endDate", java.time.LocalDate.now().toString()).param("reason", "Fever");
    JsonNode leave = call(req, st, 200);
    assertEquals("PENDING", leave.get("status").asText());
    long lid = leave.get("id").asLong(), did = leave.get("documents").get(0).get("id").asLong();
    assertEquals(absentBefore, call(get("/api/students/" + sid + "/attendance"), st, 200).get("overall").get("absent").asDouble(), "submission must not change attendance");
    // invalid uploads
    call(multipart("/api/leaves").file(new MockMultipartFile("files", "a.exe", "application/x-msdownload", "MZ".getBytes())).param("type", "OTHER").param("startDate", "2026-01-01").param("endDate", "2026-01-01").param("reason", "x"), st, 400);
    call(multipart("/api/leaves").file(new MockMultipartFile("files", "fake.pdf", "application/pdf", "not really a pdf".getBytes())).param("type", "OTHER").param("startDate", "2026-01-01").param("endDate", "2026-01-01").param("reason", "x"), st, 400);
    call(multipart("/api/leaves").param("type", "OTHER").param("startDate", "2026-01-05").param("endDate", "2026-01-01").param("reason", "x"), st, 400);
    // document access control
    assertEquals(200, mvc.perform(get("/api/leaves/" + lid + "/documents/" + did).header("Authorization", "Bearer " + m1)).andReturn().getResponse().getStatus());
    assertEquals(403, mvc.perform(get("/api/leaves/" + lid + "/documents/" + did).header("Authorization", "Bearer " + m2)).andReturn().getResponse().getStatus());
    assertEquals(403, mvc.perform(get("/api/leaves/" + lid + "/documents/" + did).header("Authorization", "Bearer " + token("1rn23is004@mentora.edu", "Student@123"))).andReturn().getResponse().getStatus());
    // review rules
    String body = "{\"approve\":true,\"comment\":\"ok\"}";
    call(put("/api/leaves/" + lid + "/review").contentType(MediaType.APPLICATION_JSON).content(body), m2, 403);
    call(put("/api/leaves/" + lid + "/review").contentType(MediaType.APPLICATION_JSON).content(body), st, 403);
    call(put("/api/leaves/" + lid + "/review").contentType(MediaType.APPLICATION_JSON).content("{\"approve\":false}"), m1, 400);
    JsonNode approved = call(put("/api/leaves/" + lid + "/review").contentType(MediaType.APPLICATION_JSON).content(body), m1, 200);
    assertEquals("APPROVED", approved.get("status").asText());
    call(put("/api/leaves/" + lid + "/review").contentType(MediaType.APPLICATION_JSON).content(body), m1, 400);
    boolean notified = false;
    for (JsonNode n : call(get("/api/notifications"), st, 200)) notified |= n.get("type").asText().equals("LEAVE_APPROVED");
    assertTrue(notified);
    assertTrue(approved.get("history").size() >= 2);
  }

  @Test @Order(8) void messageModerationOverRest() throws Exception {
    JsonNode s = login("1rn23is005@mentora.edu", "Student@123");
    String st = s.get("token").asText();
    long sid = s.get("user").get("studentId").asLong();
    call(multipart("/api/messages/" + sid).param("content", "Sir I had a fever and was absent yesterday"), st, 200);
    JsonNode blocked = call(multipart("/api/messages/" + sid).param("content", "you are such a stupid idiot"), st, 422);
    assertTrue(blocked.get("error").asText().contains("communication guidelines"));
    call(multipart("/api/messages/" + sid).param("content", "Sir I had a fever and was absent yesterday"), st, 422);          // duplicate
    call(multipart("/api/messages/" + sid).param("content", "visit http://bit.ly/free-marks"), st, 422);                      // link; 3rd violation -> temporary restriction
    JsonNode restricted = call(multipart("/api/messages/" + sid).param("content", "A perfectly polite message"), st, 422);
    assertTrue(restricted.get("error").asText().contains("temporarily restricted"));
    // another student is unaffected (no permanent/global block)
    call(multipart("/api/messages/" + login("1rn23is006@mentora.edu", "Student@123").get("user").get("studentId").asLong()).param("content", "Good evening sir"), token("1rn23is006@mentora.edu", "Student@123"), 200);
  }

  @Test @Order(9) void absenceAlertsGoOnlyToAssignedMentorWithoutDuplicates() throws Exception {
    String admin = token("admin@mentora.edu", "Admin@123"), m1 = token("mentor1@mentora.edu", "Mentor@123"), m2 = token("mentor2@mentora.edu", "Mentor@123");
    java.time.LocalDate d = java.time.LocalDate.now().minusDays(30);
    while (d.getDayOfWeek().getValue() > 5) d = d.minusDays(1);
    String bodyJson = "{\"usn\":\"1RN23IS002\",\"date\":\"" + d + "\",\"status\":\"A\"}";
    long before1 = call(get("/api/notifications/unread-count"), m1, 200).get("count").asLong(), before2 = call(get("/api/notifications/unread-count"), m2, 200).get("count").asLong();
    JsonNode first = call(post("/api/admin/attendance/mark-day").contentType(MediaType.APPLICATION_JSON).content(bodyJson), admin, 200);
    assertTrue(first.get("alertsCreated").asInt() >= 1);
    assertEquals(0, call(post("/api/admin/attendance/mark-day").contentType(MediaType.APPLICATION_JSON).content(bodyJson), admin, 200).get("alertsCreated").asInt());
    assertTrue(call(get("/api/notifications/unread-count"), m1, 200).get("count").asLong() > before1);
    assertEquals(before2, call(get("/api/notifications/unread-count"), m2, 200).get("count").asLong());
  }

  @Test @Order(10) void assistantUsesOnlyOwnStudentsAndValidatesAiOutput() throws Exception {
    String m1 = token("mentor1@mentora.edu", "Mentor@123"), m2 = token("mentor2@mentora.edu", "Mentor@123");
    when(gemini.isConfigured()).thenReturn(false);
    String ask = "{\"question\":\"Which students have attendance below 75%?\"}";
    JsonNode r1 = call(post("/api/ai/assistant").contentType(MediaType.APPLICATION_JSON).content(ask), m1, 200).get("assistant").get("payload");
    JsonNode r2 = call(post("/api/ai/assistant").contentType(MediaType.APPLICATION_JSON).content(ask), m2, 200).get("assistant").get("payload");
    assertEquals("LOW_ATTENDANCE", r1.get("operation").asText());
    assertTrue(r1.get("answer").asText().contains("Abhinav Bhardawaj"));
    assertFalse(r1.get("answer").asText().contains("Farhan"));
    assertTrue(r2.get("answer").asText().contains("Farhan Sheikh"));
    assertFalse(r2.get("answer").asText().contains("Abhinav"));
    assertFalse(r1.get("aiGenerated").asBoolean());

    // Gemini configured: narration used when valid
    when(gemini.isConfigured()).thenReturn(true);
    when(gemini.generateText(anyString(), anyString())).thenReturn(Optional.of("Only Abhinav Bhardawaj (1RN23IS004) is below the threshold."));
    JsonNode ai = call(post("/api/ai/assistant").contentType(MediaType.APPLICATION_JSON).content(ask), m1, 200).get("assistant").get("payload");
    assertTrue(ai.get("aiGenerated").asBoolean());
    // hallucinated USN of a student that is not this mentor's -> narration rejected, verified data returned
    when(gemini.generateText(anyString(), anyString())).thenReturn(Optional.of("1RN23IS013 is below the threshold."));
    JsonNode bad = call(post("/api/ai/assistant").contentType(MediaType.APPLICATION_JSON).content(ask), m1, 200).get("assistant").get("payload");
    assertFalse(bad.get("aiGenerated").asBoolean());
    assertFalse(bad.get("answer").asText().contains("1RN23IS013"));
    // Gemini failure does not break the feature
    when(gemini.generateText(anyString(), anyString())).thenReturn(Optional.empty());
    assertFalse(call(post("/api/ai/assistant").contentType(MediaType.APPLICATION_JSON).content(ask), m1, 200).get("assistant").get("payload").get("aiGenerated").asBoolean());
    // students cannot use the assistant
    call(post("/api/ai/assistant").contentType(MediaType.APPLICATION_JSON).content(ask), token("1rn23is003@mentora.edu", "Student@123"), 403);
    // conversation history is private
    long cid = call(post("/api/ai/assistant").contentType(MediaType.APPLICATION_JSON).content(ask), m1, 200).get("conversationId").asLong();
    call(get("/api/ai/conversations/" + cid), m2, 403);
    call(get("/api/ai/conversations/" + cid), m1, 200);
  }

  @Test @Order(11) void adminCanCreateUsersAssignAndImport() throws Exception {
    String admin = token("admin@mentora.edu", "Admin@123");
    long dept = call(get("/api/admin/departments"), admin, 200).get(0).get("id").asLong();
    JsonNode created = call(post("/api/admin/students").contentType(MediaType.APPLICATION_JSON)
        .content("{\"name\":\"Test Student\",\"email\":\"test.student@mentora.edu\",\"password\":\"Passw0rd!\",\"usn\":\"1RN23IS099\",\"semester\":5,\"departmentId\":" + dept + "}"), admin, 200);
    call(post("/api/admin/students").contentType(MediaType.APPLICATION_JSON)
        .content("{\"name\":\"Dup\",\"email\":\"test.student@mentora.edu\",\"password\":\"Passw0rd!\",\"usn\":\"1RN23IS098\",\"semester\":5,\"departmentId\":" + dept + "}"), admin, 400);
    long mentorId = call(get("/api/admin/mentors"), admin, 200).get(0).get("id").asLong();
    call(post("/api/admin/assignments").contentType(MediaType.APPLICATION_JSON).content("{\"studentId\":" + created.get("id").asLong() + ",\"facultyId\":" + mentorId + "}"), admin, 200);
    call(get("/api/auth/me"), token("test.student@mentora.edu", "Passw0rd!"), 200);

    String csv = "usn,subjectCode,component,score\n1RN23IS099,23IS501,IA1,20\n1RN23IS099,23IS501,IA1,21\nNOPE,23IS501,IA1,5\n1RN23IS099,23IS501,IA2,99\n1RN23IS099,23IS501,IA2,AB\n";
    JsonNode rep = call(multipart("/api/admin/import/marks").file(new MockMultipartFile("file", "m.csv", "text/csv", csv.getBytes())), admin, 200);
    assertEquals(5, rep.get("total").asInt());
    assertEquals(2, rep.get("inserted").asInt());
    assertEquals(1, rep.get("duplicates").asInt());
    assertEquals(2, rep.get("failed").asInt());
    // assessment rules are configurable and used by the calculator
    call(put("/api/admin/settings/ia.converted_max").contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"-3\"}"), admin, 400);
    call(put("/api/admin/settings/ia.converted_max").contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"30\"}"), admin, 200);
    call(put("/api/admin/settings/ia.converted_max").contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"25\"}"), admin, 200);
  }

  @Test @Order(13) void restartAfterAdminRenamesDemoAccountsDoesNotBreakSeeding(@Autowired com.mentora.service.DemoDataSeeder seeder,
                                                                                @Autowired com.mentora.repo.UserRepository users,
                                                                                @Autowired com.mentora.repo.StudentRepository students) throws Exception {
    String admin = token("admin@mentora.edu", "Admin@123");
    long mentorId = call(get("/api/admin/mentors"), admin, 200).get(0).get("id").asLong();
    call(put("/api/admin/mentors/" + mentorId).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"renamed.mentor@mentora.edu\"}"), admin, 200);
    long before = students.count();
    seeder.run(new org.springframework.boot.DefaultApplicationArguments());      // simulates the next application start
    assertEquals(before, students.count(), "seeding must not duplicate or fail");
    assertTrue(users.findByEmailIgnoreCase("renamed.mentor@mentora.edu").isPresent());
    call(get("/api/auth/me"), token("renamed.mentor@mentora.edu", "Mentor@123"), 200);   // renamed account still works
  }

  @Test @Order(12) void mentorManagesOwnRosterAndAttendance() throws Exception {
    String admin = token("admin@mentora.edu", "Admin@123"), m1 = token("mentor1@mentora.edu", "Mentor@123"), m2 = token("mentor2@mentora.edu", "Mentor@123");
    String stuTok = token("1rn23is001@mentora.edu", "Student@123");
    long dept = call(get("/api/admin/departments"), admin, 200).get(0).get("id").asLong();
    // an unassigned student appears as available and can be added by a mentor
    JsonNode created = call(post("/api/admin/students").contentType(MediaType.APPLICATION_JSON)
        .content("{\"name\":\"Roster Test\",\"email\":\"roster.test@mentora.edu\",\"password\":\"Passw0rd!\",\"usn\":\"1RN23IS097\",\"semester\":5,\"departmentId\":" + dept + "}"), admin, 200);
    long newId = created.get("id").asLong();
    assertEquals(1, call(get("/api/mentor/students/available").param("search", "1RN23IS097"), m1, 200).size());
    call(post("/api/mentor/students").contentType(MediaType.APPLICATION_JSON).content("{\"usn\":\"1RN23IS097\"}"), stuTok, 403);   // students cannot
    call(post("/api/mentor/students").contentType(MediaType.APPLICATION_JSON).content("{\"usn\":\"1RN23IS097\"}"), m1, 200);
    call(post("/api/mentor/students").contentType(MediaType.APPLICATION_JSON).content("{\"usn\":\"1RN23IS097\"}"), m1, 400);       // already in list
    call(post("/api/mentor/students").contentType(MediaType.APPLICATION_JSON).content("{\"usn\":\"1RN23IS097\"}"), m2, 400);       // belongs to m1 now
    call(post("/api/mentor/students").contentType(MediaType.APPLICATION_JSON).content("{\"usn\":\"1RN23IS011\"}"), m1, 400);       // cannot take another mentor's student
    call(get("/api/students/" + newId + "/profile"), m1, 200);
    call(get("/api/students/" + newId + "/profile"), m2, 403);
    // mentor cannot remove another mentor's student; can remove their own, then loses access
    long otherMentorsStudent = call(get("/api/mentor/students").param("search", "1RN23IS011"), m2, 200).get("content").get(0).get("studentId").asLong();
    call(delete("/api/mentor/students/" + otherMentorsStudent), m1, 403);
    call(delete("/api/mentor/students/" + newId), m1, 200);
    call(get("/api/students/" + newId + "/profile"), m1, 403);
    assertEquals(1, call(get("/api/mentor/students/available").param("search", "1RN23IS097"), m2, 200).size());

    // attendance editing
    long own = call(get("/api/mentor/students").param("search", "1RN23IS002"), m1, 200).get("content").get(0).get("studentId").asLong();
    String today = java.time.LocalDate.now().toString();
    String body = "{\"date\":\"" + today + "\",\"entries\":[{\"studentId\":" + own + ",\"status\":\"A\"}]}";
    assertEquals(1, call(post("/api/mentor/attendance/mark").contentType(MediaType.APPLICATION_JSON).content(body), m1, 200).get("updated").asInt());
    String status = null;
    for (JsonNode d : call(get("/api/mentor/attendance/day").param("date", today), m1, 200)) if (d.get("studentId").asLong() == own) status = d.get("status").asText();
    assertEquals("A", status);
    call(post("/api/mentor/attendance/mark").contentType(MediaType.APPLICATION_JSON).content(body.replace("\"A\"", "\"P\"")), m1, 200);   // can correct it
    // all-or-nothing and authorization
    String mixed = "{\"date\":\"" + today + "\",\"entries\":[{\"studentId\":" + own + ",\"status\":\"A\"},{\"studentId\":" + otherMentorsStudent + ",\"status\":\"A\"}]}";
    call(post("/api/mentor/attendance/mark").contentType(MediaType.APPLICATION_JSON).content(mixed), m1, 403);
    for (JsonNode d : call(get("/api/mentor/attendance/day").param("date", today), m1, 200)) if (d.get("studentId").asLong() == own) assertEquals("P", d.get("status").asText(), "failed batch must not change anything");
    call(post("/api/mentor/attendance/mark").contentType(MediaType.APPLICATION_JSON).content(body.replace(today, java.time.LocalDate.now().plusDays(1).toString())), m1, 400);
    call(post("/api/mentor/attendance/mark").contentType(MediaType.APPLICATION_JSON).content(body.replace(today, java.time.LocalDate.now().minusDays(200).toString())), m1, 400);
    call(post("/api/mentor/attendance/mark").contentType(MediaType.APPLICATION_JSON).content(body), stuTok, 403);
    call(post("/api/mentor/attendance/mark").contentType(MediaType.APPLICATION_JSON).content("{\"date\":\"" + today + "\",\"entries\":[]}"), m1, 400);
  }
}
