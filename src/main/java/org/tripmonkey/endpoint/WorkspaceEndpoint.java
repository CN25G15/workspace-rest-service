package org.tripmonkey.endpoint;

import io.quarkus.grpc.GrpcClient;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logmanager.Logger;
import org.tripmonkey.domain.data.User;
import org.tripmonkey.proto.ProtoSerde;
import org.tripmonkey.rest.domain.WorkspacePatch;
import org.tripmonkey.rest.patch.Patch;
import org.tripmonkey.workspace.service.PatchApplier;

import org.tripmonkey.workspace.service.WorkspaceRequest;
import org.tripmonkey.workspace.service.WorkspaceRequester;


@Path("/workspace/{uuid}")
public class WorkspaceEndpoint {

    @GrpcClient
    PatchApplier pac;

    @GrpcClient
    WorkspaceRequester wrc;

    @PATCH
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<Response> processPatch(@PathParam("uuid") String uuid, @CookieParam("user") String user, Patch p) {
        return Uni.createFrom().optional(User.from(user)).log(String.format("Received Patch for workspace with id %s", uuid))
                .onItem().ifNull().failWith(() -> new RuntimeException("Invalid UUID for user"))
                        .onItem().ifNotNull().transform(user1 -> WorkspacePatch.from(uuid, user1.toString(), p))
                        .onItem().transform(workspacePatch -> ProtoSerde.workspacePatchMapper.serialize(workspacePatch))
                        .onItem().transformToUni(pac::apply).onItem()
                .transform(status -> Response.status((int) status.getStatus()).entity(status.getMessage()).build())
                .onFailure().recoverWithItem(throwable -> Response.status(400).entity(throwable.getMessage()).build());
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<Response> fetchWorkspace(@PathParam("uuid") String uuid, @CookieParam("user") String user){
        return wrc.fetch(WorkspaceRequest.newBuilder().setWid(uuid).build()).onItem().transform(workspaceResponse -> {
            Response r = Response.status(404).build();
            if(workspaceResponse.hasWorkspace()) {
                r = Response.accepted().entity(ProtoSerde.workspaceMapper.deserialize(workspaceResponse.getWorkspace())).build();
            }
            return r;
        });
    }

}
