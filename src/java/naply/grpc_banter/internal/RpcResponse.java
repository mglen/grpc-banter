package naply.grpc_banter.internal;

import com.google.protobuf.Message;
import io.grpc.Metadata;
import io.grpc.Status;

public class RpcResponse {
    private final Message message;
    private final Metadata headers;
    private final Metadata trailers;
    private final Status status;

    public static Builder builder() {
        return new Builder();
    }

    private RpcResponse(Message message, Metadata headers, Metadata trailers, Status status) {
        this.message = message;
        this.headers = headers;
        this.trailers = trailers;
        this.status = status;
    }

    public Message getMessage() {
        return message;
    }

    public Metadata getHeaders() {
        return headers;
    }

    public Metadata getTrailers() {
        return trailers;
    }

    public Status getStatus() {
        return status;
    }

    public static class Builder {
        private Message message;
        private Metadata headers;
        private Metadata trailers;
        private Status status;

        public Builder message(Message message) {
            this.message = message;
            return this;
        }

        public Builder headers(Metadata headers) {
            this.headers = headers;
            return this;
        }

        public Builder trailers(Metadata trailers) {
            this.trailers = trailers;
            return this;
        }

        public Builder status(Status status) {
            this.status = status;
            return this;
        }

        public RpcResponse build() {
            return new RpcResponse(message, headers, trailers, status);
        }
    }
}
