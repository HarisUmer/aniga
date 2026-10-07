package com.example.aniga.data;

import com.example.aniga.data.model.AuthResponse;
import com.example.aniga.data.model.Chapter;
import com.example.aniga.data.model.ChapterPages;
import com.example.aniga.data.model.ChatMessage;
import com.example.aniga.data.model.ChatRoom;
import com.example.aniga.data.model.Credentials;
import com.example.aniga.data.model.FriendRequest;
import com.example.aniga.data.model.LibraryState;
import com.example.aniga.data.model.Match;
import com.example.aniga.data.model.Media;
import com.example.aniga.data.model.MediaDetail;
import com.example.aniga.data.model.Outcome;
import com.example.aniga.data.model.Person;
import com.example.aniga.data.model.Personality;
import com.example.aniga.data.model.Recommendation;
import com.example.aniga.data.model.SearchResults;
import com.example.aniga.data.model.Series;
import com.example.aniga.data.model.ShelfItem;
import com.example.aniga.data.model.SourceReport;
import com.example.aniga.data.model.SourceView;
import com.example.aniga.data.model.Taste;
import com.example.aniga.data.model.TasteV2;
import com.example.aniga.data.model.User;

import java.util.List;
import java.util.Map;

import okhttp3.MultipartBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.GET;
import retrofit2.http.Multipart;
import retrofit2.http.POST;
import retrofit2.http.PUT;
import retrofit2.http.Part;
import retrofit2.http.Path;
import retrofit2.http.Query;

public interface AnigaService {

    @POST("api/auth/signup")
    Call<AuthResponse> signup(@Body Credentials body);

    @POST("api/auth/login")
    Call<AuthResponse> login(@Body Credentials body);

    @GET("api/auth/me")
    Call<User> me();

    @POST("api/reports")
    Call<SourceReport.Posted> report(@Body SourceReport body);

    // --- Me (bound to the session user, no id in the path) ---

    @GET("api/me/recommendations")
    Call<List<Recommendation>> myRecommendations(@Query("limit") int limit, @Query("type") String type,
                                                 @Query("track") boolean track);

    // --- People: friends, requests, matches, personality, shared shelves ---

    @GET("api/people/friends")
    Call<List<Person>> friends();

    @GET("api/people/requests")
    Call<List<FriendRequest>> friendRequests();

    @POST("api/people/requests")
    Call<Outcome> addFriend(@Body Map<String, String> body);

    @POST("api/people/requests/{id}/accept")
    Call<Outcome> acceptRequest(@Path("id") long id);

    @POST("api/people/requests/{id}/decline")
    Call<Outcome> declineRequest(@Path("id") long id);

    @GET("api/people/matches")
    Call<List<Match>> matches();

    @GET("api/people/personality")
    Call<Personality> personality();

    @GET("api/people/{id}/shelf")
    Call<List<ShelfItem>> shelf(@Path("id") long userId);

    // --- Chat: global room, direct and group rooms, messages ---

    @GET("api/chat/rooms")
    Call<List<ChatRoom>> chatRooms();

    @GET("api/chat/global")
    Call<ChatRoom> globalRoom();

    @POST("api/chat/direct")
    Call<ChatRoom> openDirect(@Body Map<String, String> body);

    /** Stable public link to this account's ratings and taste report. */
    @POST("api/me/report")
    Call<com.example.aniga.data.model.ReportLink> tasteReport();

    @POST("api/chat/group")
    Call<ChatRoom> openGroup(@Body Map<String, Object> body);

    @GET("api/chat/rooms/{id}/messages")
    Call<List<ChatMessage>> chatMessages(@Path("id") long roomId);

    @POST("api/chat/rooms/{id}/messages")
    Call<ChatMessage> sendMessage(@Path("id") long roomId, @Body Map<String, String> body);

    /** body + optional share card; Gson serializes the nested share object for the backend. */
    @POST("api/chat/rooms/{id}/messages")
    Call<ChatMessage> sendShare(@Path("id") long roomId, @Body Map<String, Object> body);

    @GET("api/media")
    Call<List<Media>> media(@Query("q") String query, @Query("type") String type);

    @GET("api/media")
    Call<List<Media>> browse(@Query("type") String type, @Query("genre") String genre, @Query("sort") String sort,
                             @Query("limit") int limit, @Query("offset") int offset);

    @GET("api/media/{id}")
    Call<MediaDetail> detail(@Path("id") long id);

    @GET("api/media/{id}/similar")
    Call<List<Media>> similar(@Path("id") long id, @Query("limit") int limit);

    @GET("api/users")
    Call<List<User>> users();

    @GET("api/users/{id}/recommendations")
    Call<List<Recommendation>> recommendations(@Path("id") long userId,
                                               @Query("limit") int limit,
                                               @Query("type") String type);

    /** track=true logs the list as shown, so a later CLICK on one of these titles counts as a reward. */
    @GET("api/users/{id}/recommendations")
    Call<List<Recommendation>> recommendations(@Path("id") long userId,
                                               @Query("limit") int limit,
                                               @Query("type") String type,
                                               @Query("track") boolean track);

    @GET("api/users/{id}/taste")
    Call<Taste> taste(@Path("id") long userId);

    @GET("api/users/{id}/taste/v2")
    Call<TasteV2> tasteV2(@Path("id") long userId);

    /** kind: LIKE, NOT_INTERESTED, CLICK or CLEAR. */
    @POST("api/users/{id}/feedback")
    Call<Map<String, Object>> feedback(@Path("id") long userId, @Body TasteV2.Feedback body);

    /** weight: 1 boost, -1 mute, 0 clear. */
    @PUT("api/users/{id}/prefs")
    Call<Map<String, Object>> pref(@Path("id") long userId, @Body TasteV2.Pref body);

    @DELETE("api/users/{id}/taste/online")
    Call<Void> resetOnline(@Path("id") long userId);

    @GET("api/users/{id}/library")
    Call<List<LibraryState.Entry>> library(@Path("id") long userId, @Query("status") String status,
                                           @Query("type") String type);

    @GET("api/users/{id}/library/{mediaId}")
    Call<LibraryState> libraryEntry(@Path("id") long userId, @Path("mediaId") long mediaId);

    @GET("api/users/{id}/updates")
    Call<List<com.example.aniga.data.model.LibraryUpdate>> updates(@Path("id") long userId);

    @PUT("api/users/{id}/library/{mediaId}")
    Call<LibraryState> updateLibrary(@Path("id") long userId, @Path("mediaId") long mediaId,
                                     @Body LibraryState.Update body);

    @GET("api/read/sources")
    Call<List<SourceView>> sources();

    @GET("api/read/{source}/popular")
    Call<List<Series>> popular(@Path("source") String source, @Query("lang") String lang, @Query("page") int page);

    @GET("api/read/{source}/search")
    Call<List<Series>> searchSource(@Path("source") String source, @Query("q") String q, @Query("lang") String lang);

    @GET("api/read/find")
    Call<List<Series>> find(@Query("title") String title, @Query("lang") String lang,
                            @Query("aliases") String aliases, @Query("type") String type);

    @GET("api/people/search")
    Call<List<Person>> searchPeople(@Query("q") String query);

    @GET("api/people/requests/outgoing")
    Call<List<FriendRequest>> outgoingRequests();

    @GET("api/read/{source}/series/{id}/chapters")
    Call<List<Chapter>> chapters(@Path("source") String source, @Path("id") String seriesId, @Query("lang") String lang);

    @GET("api/read/{source}/chapters/{id}/pages")
    Call<ChapterPages> pages(@Path("source") String source, @Path("id") String chapterId, @Query("lang") String lang);

    @GET("api/search")
    Call<List<SearchResults.Hit>> describe(@Query("q") String q, @Query("type") String type, @Query("limit") int limit);

    @Multipart
    @POST("api/search/image")
    Call<SearchResults.Image> searchImage(@Part MultipartBody.Part file, @Query("scenes") boolean scenes);
}
