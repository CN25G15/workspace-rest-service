package org.tripmonkey.endpoint;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import io.micrometer.core.annotation.Counted;
import io.quarkus.grpc.GrpcClient;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.tripmonkey.domain.data.User;
import org.tripmonkey.domain.data.Workspace;
import org.tripmonkey.domain.patch.PatchBuilder;
import org.tripmonkey.patch.data.JsonPatch;
import org.tripmonkey.proto.map.ProtoMapper;
import org.tripmonkey.rest.domain.WorkspacePatch;
import org.tripmonkey.rest.patch.Patch;
import org.tripmonkey.workspace.service.PatchApplier;

import org.tripmonkey.workspace.service.WorkspaceCreator;
import org.tripmonkey.workspace.service.WorkspaceRequest;
import org.tripmonkey.workspace.service.WorkspaceRequester;


@Path("workspace")
public class WorkspaceEndpoint {

    @GrpcClient("pac")
    PatchApplier pac;

    @GrpcClient("wrc")
    WorkspaceRequester wrc;

    @GrpcClient("wrkc")
    WorkspaceCreator wrkc;

    @Counted(value = "total.create.workspace.requests", extraTags = {"integer","totalCounter"})
    @POST
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<Response> createWorkspace(@CookieParam("user") String user) {
        return Uni.createFrom().optional(User.from(user)).log(String.format("Received request to create workspace for user %s.", user))
                .onItem().ifNull().failWith(() -> new RuntimeException("Invalid UUID for user"))
                .onItem().ifNotNull().transform(ProtoMapper.userMapper::serialize)
                .onItem().transformToUni(wrkc::create).onItem()
                .transform(workspaceResponse -> {
                    try {
                        return Response.status(200).entity(JsonFormat.printer().print(workspaceResponse)).build();
                    } catch (InvalidProtocolBufferException e) {
                        throw new RuntimeException(e);
                    }
                })
                .onFailure().recoverWithItem(throwable -> Response.status(400).entity(throwable.getMessage()).build());
    }

    @Counted(value = "total.patch.workspace.requests", extraTags = {"integer","totalCounter"})
    @PATCH
    @Path("{uuid}")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<Response> processPatch(@PathParam("uuid") String uuid, @CookieParam("user") String user, Patch p) { //formerly Patch
        return Uni.createFrom().optional(User.from(user))
                .log(String.format("Received Patch for workspace with id %s", uuid))
                .log(String.format("Patch received %s", p.toString()))
                .onItem().ifNull().failWith(() -> new RuntimeException("Invalid UUID for user"))
                        .onItem().ifNotNull().transform(user1 -> PatchBuilder.from(uuid, user1, p))
                        .onItem().ifNull().failWith(() -> new RuntimeException("Invalid Request"))
                        .onItem().ifNotNull().transform(patch -> ProtoMapper.workspacePatchMapper.serialize(patch))
                        .onItem().transformToUni(pac::apply).onItem()
                .transform(status -> Response.status((int) status.getStatus()).entity(status.getMessage()).build())
                .onFailure().recoverWithItem(throwable -> Response.status(400).entity(throwable.getMessage()).build());
    }

    @Counted(value = "total.fetch.workspace.requests", extraTags = {"integer","totalCounter"})
    @GET
    @Path("{uuid}")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<Response> fetchWorkspace(@PathParam("uuid") String uuid, @CookieParam("user") String user){
        return wrc.fetch(WorkspaceRequest.newBuilder().setWid(uuid).build()).onItem().transform(workspaceResponse -> {
            Response r = Response.status(404).build();
            if(workspaceResponse.hasWorkspace()) {
                Workspace ws = ProtoMapper.workspaceMapper.deserialize(workspaceResponse.getWorkspace());
                if(ws.getCollaborators().stream().map(User::toString).anyMatch(s -> s.equals(user))) {
                    try {
                        r = Response.accepted().entity(JsonFormat.printer().print(workspaceResponse.getWorkspace())).build();
                    } catch (InvalidProtocolBufferException e) {
                        r = Response.status(500).build();
                    }
                }  else {
                    r = Response.status(403).build();
                }
            }
            return r;
        });
    }

}
