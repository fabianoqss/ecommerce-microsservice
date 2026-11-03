export interface AuthUser {
  sub: string;
  name: string | null;
  email: string;
  roles: string[];
}
