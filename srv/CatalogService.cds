using { my.book as book } from '../db/schema';

service CatalogService {
  entity Books as projection on book.Books;
}
